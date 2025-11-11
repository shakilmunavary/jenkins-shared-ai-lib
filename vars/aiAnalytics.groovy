def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  def TMP_DIR = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
  sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

  stage('Clone Repos') {
    dir(TMP_DIR) {
      sh """
        git clone '${terraformRepo}' terraform-code
        git clone 'https://github.com/shakilmunavary/jenkins-shared-ai-lib.git' jenkins-shared-ai-lib
      """
    }
  }

  stage('Terraform Init & Plan') {
    dir("${TMP_DIR}/terraform-code/${folderName}") {
      sh """
        terraform init
        terraform plan -out=tfplan.binary
        terraform show -json tfplan.binary > tfplan.raw.json

        jq '
          .resource_changes |= sort_by(.address) |
          del(.resource_changes[].change.after_unknown) |
          del(.resource_changes[].change.before_sensitive) |
          del(.resource_changes[].change.after_sensitive) |
          del(.resource_changes[].change.after_identity) |
          del(.resource_changes[].change.before) |
          del(.resource_changes[].change.after.tags_all) |
          del(.resource_changes[].change.after.tags) |
          del(.resource_changes[].change.after.id) |
          del(.resource_changes[].change.after.arn)
        ' tfplan.raw.json > tfplan.json
      """
    }
  }

  stage('Generate Resource × Rule Matrix') {
    dir("${TMP_DIR}/terraform-code/${folderName}") {
      sh '''
        set -euo pipefail

        PLAN=tfplan.json
        GUARDRAILS=$WORKSPACE/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt
        MATRIX=resource_rule_matrix.txt
        : > "$MATRIX"

        RESOURCES=$(jq -r ".resource_changes[].address" "$PLAN" | sort -u)

        for RES in $RESOURCES; do
          TYPE=$(echo "$RES" | cut -d"." -f1)

          # Extract all Rule IDs for this resource type
          grep -A5 -E "Resource Type:[[:space:]]*$TYPE" "$GUARDRAILS" | \
            grep "Rule ID:" | while read -r RULELINE; do
              RULEID=$(echo "$RULELINE" | sed -n "s/.*Rule ID:[[:space:]]*\\([^]]*\\)].*/\\1/p")
              RULEDESC=$(grep -A1 "$RULELINE" "$GUARDRAILS" | grep "^Rule:" | sed "s/^Rule:[[:space:]]*//")
              [ -z "$RULEDESC" ] && RULEDESC="Rule description not found"
              printf "%s\\t%s\\t%s\\n" "$RES" "$RULEID" "$RULEDESC" >> "$MATRIX"
            done
        done

        echo "Matrix generated at: $MATRIX"
        wc -l "$MATRIX" || true
      '''
    }
  }

  stage('AI analytics with Azure OpenAI') {
    withCredentials([
      string(credentialsId: 'AZURE_API_KEY',         variable: 'AZURE_API_KEY'),
      string(credentialsId: 'AZURE_API_BASE',        variable: 'AZURE_API_BASE'),
      string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
      string(credentialsId: 'AZURE_API_VERSION',     variable: 'AZURE_API_VERSION')
    ]) {
      def tfDir          = "${TMP_DIR}/terraform-code/${folderName}"
      def sharedLibDir   = "${TMP_DIR}/jenkins-shared-ai-lib"
      def tfPlanJsonPath = "${tfDir}/tfplan.json"
      def guardrailsPath = "${sharedLibDir}/guardrails/guardrails_v1.txt"
      def templatePath   = "${sharedLibDir}/reference_terra_analysis_html.html"
      def matrixPath     = "${tfDir}/resource_rule_matrix.txt"
      def outputHtmlPath = "${tfDir}/output.html"
      def payloadPath    = "${tfDir}/payload.json"
      def responsePath   = "${outputHtmlPath}.raw"

      sh """
        PLAN_FILE_CONTENT=\$(jq -Rs . < "${tfPlanJsonPath}")
        GUARDRAILS_CONTENT=\$(jq -Rs . < "${guardrailsPath}")
        SAMPLE_HTML=\$(jq -Rs . < "${templatePath}")
        MATRIX_CONTENT=\$(jq -Rs . < "${matrixPath}")

        cat <<'EOF' > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
      "content": "You are a Terraform compliance auditor.\\nYou will receive four input files:\\n1) Terraform Plan JSON,\\n2) Guardrails Checklist,\\n3) Sample HTML Template,\\n4) Resource × Rule Matrix.\\n\\nReturn a single HTML document with these sections:\\n\\n1️⃣ Change Summary Table\\n- Title: 'What's Being Changed'\\n- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details\\n- Ensure resource count matches Terraform plan\\n\\n2️⃣ Terraform Code Recommendations\\n- Provide actionable suggestions to improve code quality\\n\\n3️⃣ Security and Compliance Recommendations\\n- Highlight misconfigurations and generic recommendations\\n\\n4️⃣ Guardrail Compliance Summary\\n- Title: 'Guardrail Compliance Summary'\\n- Columns: Terraform Resource, Rule Id, Rule, Status (PASS or FAIL)\\n- Evaluate each row in the Resource × Rule Matrix\\n- No N/A values; every rule must be PASS or FAIL\\n- Calculate Overall Guardrail Coverage % = (PASS / total rules evaluated) × 100\\n\\n5️⃣ Overall Status\\n- PASS if coverage ≥ 90%, else FAIL\\n\\n6️⃣ HTML Formatting\\n- Copy the <html>, <head>, <body> structure from the Sample HTML Template\\n- Use <h2>, <h3> for headings\\n- Use <table>, <thead>, <tbody>, <tr>, <th>, <td> for tables\\n- Do not output Markdown, LaTeX, or code fences\\n- Return only valid HTML that Jenkins publishHTML can render"
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": ${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": ${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": ${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Resource × Rule Matrix:\\n" },
    { "role": "user", "content": ${MATRIX_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        jq -r '.choices[0].message.content' "${responsePath}" | sed '/^```/d' > "${outputHtmlPath}"
      """
    }
  }

  stage('Publish Report') {
    publishHTML([
      reportName: 'AI Analysis',
      reportDir: "${TMP_DIR}/terraform-code/${folderName}",
      reportFiles: 'output.html',
      keepAll: true,
      allowMissing: false,
      alwaysLinkToLastBuild: true
    ])
  }

  stage('Evaluate Guardrail Coverage') {
    def outputHtml = "${TMP_DIR}/terraform-code/${folderName}/output.html"
    def passCount  = sh(script: "grep -oi 'class=\"pass\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
    def failCount  = sh(script: "grep -oi 'class=\"fail\"' '${outputHtml}' | wc -l",  returnStdout: true).trim().toInteger()
    def coverage   = (passCount + failCount) > 0 ? (passCount * 100 / (passCount + failCount)).toInteger() : 0

    echo "Guardrail Coverage: ${coverage}%"
    env.PIPELINE_DECISION     = coverage >= 90 ? 'APPROVED' : 'REJECTED'
    currentBuild.description  = "Decision: ${env.PIPELINE_DECISION} (Coverage: ${coverage}%)"
  }

  stage('Decision') {
    if (env.PIPELINE_DECISION == 'APPROVED') {
      echo "✅ Pipeline approved. Proceeding..."
    } else {
      echo "❌ Pipeline rejected. Halting..."
    }
  }
}
