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
        PLAN=tfplan.json
        GUARDRAILS=$WORKSPACE/jenkins-shared-ai-lib/guardrails/guardrails_v1.txt
        MATRIX=resource_rule_matrix.txt
        rm -f "$MATRIX"

        RESOURCES=$(jq -r ".resource_changes[].address" "$PLAN")

        for RES in $RESOURCES; do
          TYPE=$(echo "$RES" | cut -d"." -f1)
          awk -v type="$TYPE" '
            $0 ~ "Resource Type:" && $3 == type {flag=1}
            /^

\[/ {if(flag) {print; flag=0}}
          ' "$GUARDRAILS" | while read RULELINE; do
            RULEID=$(echo "$RULELINE" | sed -n "s/.*Rule ID: \\([^]]*\\)].*/\\1/p")
            RULEDESC=$(grep -A1 "$RULELINE" "$GUARDRAILS" | grep "Rule:" | sed "s/Rule: //")
            echo -e "${RES}\\t${RULEID}\\t${RULEDESC}" >> "$MATRIX"
          done
        done
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

        cat <<EOF > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
      "content": "
You are a Terraform compliance auditor. You will receive four input files:
1) Terraform Plan JSON,
2) Guardrails Checklist,
3) Sample HTML Template,
4) Pre-expanded Resource × Rule Matrix.

Your task is to evaluate each row in the matrix and output PASS or FAIL based on the plan.
Do not skip any row. The number of rows in the Guardrail Compliance Summary must equal the number of rows in the matrix.
At the end, calculate Overall Guardrail Coverage % = (PASS / total rules evaluated) × 100.
"
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": \${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Resource × Rule Matrix:\\n" },
    { "role": "user", "content": \${MATRIX_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        if jq -e '.choices[0].message.content' "${responsePath}" > /dev/null; then
          jq -r '.choices[0].message.content' "${responsePath}" > "${outputHtmlPath}"
        else
          echo "<html><body><h2>⚠️ AI response was empty or malformed</h2></body></html>" > "${outputHtmlPath}"
        fi
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

    echo "🔍 Guardrail Coverage: ${coverage}%"
    sh "sed -i 's/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: ${coverage}%/' '${outputHtml}'"

    env.PIPELINE_DECISION     = coverage >= 50 ? 'APPROVED' : 'REJECTED'
    currentBuild.description  = "Auto-${env.PIPELINE_DECISION.toLowerCase()} (Coverage: ${coverage}%)"
  }

  stage('Decision') {
    if (env.PIPELINE_DECISION == 'APPROVED') {
      echo "✅ Pipeline approved. Proceeding..."
    } else {
      echo "❌ Pipeline rejected. Halting..."
    }
  }
}
