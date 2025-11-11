def call(Map config) {
  def terraformRepo = config.terraformRepo
  def folderName    = config.folderName

  // Isolated temp workspace inside Jenkins job folder
  def TMP_DIR = "${env.WORKSPACE}/tmp-${env.BUILD_ID}"
  sh "rm -rf '${TMP_DIR}' && mkdir -p '${TMP_DIR}'"

  stage('Clone Repos') {
    dir(TMP_DIR) {
      sh """
        echo "📦 Cloning Terraform Repo"
        rm -rf terraform-code
        git clone '${terraformRepo}' terraform-code

        echo "📦 Cloning Shared AI Library"
        rm -rf jenkins-shared-ai-lib
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

  stage('AI analytics with Azure OpenAI') {
    withCredentials([
      string(credentialsId: 'AZURE_API_KEY',         variable: 'AZURE_API_KEY'),
      string(credentialsId: 'AZURE_API_BASE',        variable: 'AZURE_API_BASE'),
      string(credentialsId: 'AZURE_DEPLOYMENT_NAME', variable: 'DEPLOYMENT_NAME'),
      string(credentialsId: 'AZURE_API_VERSION',     variable: 'AZURE_API_VERSION')
    ]) {
      def tfDir            = "${TMP_DIR}/terraform-code/${folderName}"
      def sharedLibDir     = "${TMP_DIR}/jenkins-shared-ai-lib"
      def tfPlanJsonPath   = "${tfDir}/tfplan.json"
      def guardrailsPath   = "${sharedLibDir}/guardrails/guardrails_v1.txt"
      def templatePath     = "${sharedLibDir}/reference_terra_analysis_html.html"
      def outputHtmlPath   = "${tfDir}/output.html"
      def payloadPath      = "${tfDir}/payload.json"
      def responsePath     = "${outputHtmlPath}.raw"

      sh """
        echo "📄 Escaping input files for payload"
        PLAN_FILE_CONTENT=\$(jq -Rs . < "${tfPlanJsonPath}")
        GUARDRAILS_CONTENT=\$(jq -Rs . < "${guardrailsPath}")
        SAMPLE_HTML=\$(jq -Rs . < "${templatePath}")

        echo "🧠 Constructing deterministic prompt for Azure OpenAI"
        cat <<EOF > "${payloadPath}"
{
  "messages": [
    {
      "role": "system",
        "content": "You are a Terraform compliance auditor. You will receive three input files: 1) Terraform Plan in JSON format, 2) Guardrails Checklist (versioned), and 3) Sample HTML Template.\\n\\nYour task is to analyze the Terraform plan against the guardrails and return a single HTML output with the following sections:\\n\\n1️⃣ Change Summary Table\\n- Title: 'What's Being Changed'\\n- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details\\n- Ensure resource count matches Terraform plan\\n\\n2️⃣ Terraform Code Recommendations\\n- Actionable suggestions to improve code quality\\n\\n3️⃣ Security and Compliance Recommendations\\n- Highlight misconfigurations and generic recommendations\\n\\n4️⃣ Guardrail Compliance Summary\\n- Title: 'Guardrail Compliance Summary'\\n- Columns: Terraform Resource, Rule Id, Rule, Status (PASS or FAIL)\\n- For every resource type present in the Terraform plan, evaluate **all rules** defined for that type in the guardrails file.\\n- Output one row per (Terraform Resource, Rule ID). Do not skip any rule for a resource type that exists in the plan.\\n- Ensure the number of rows equals (#rules defined for that resource type × #resources of that type in the plan).\\n- At the end, calculate Overall Guardrail Coverage % = (PASS / total rules evaluated) × 100.\\n\\n5️⃣ Overall Status\\n- Status: PASS if coverage ≥ 90%, else FAIL\\n\\n6️⃣ HTML Formatting\\n- Match visual structure of sample HTML using semantic tags and inline styles"

    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": \${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File (Versioned):\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        echo "📡 Sending payload to Azure OpenAI"
        curl -s -X POST "\${AZURE_API_BASE}/openai/deployments/\${DEPLOYMENT_NAME}/chat/completions?api-version=\${AZURE_API_VERSION}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: \${AZURE_API_KEY}" \\
             -d @"${payloadPath}" > "${responsePath}"

        echo "📥 Parsing response and writing output"
        if jq -e '.choices[0].message.content' "${responsePath}" > /dev/null; then
          jq -r '.choices[0].message.content' "${responsePath}" > "${outputHtmlPath}"
        else
          echo "<html><body><h2>⚠️ AI response was empty or malformed</h2><p>Please check payload formatting and Azure OpenAI status.</p></body></html>" > "${outputHtmlPath}"
        fi

        echo "🧾 Logging normalized plan and raw response for audit"
        cp "${tfPlanJsonPath}" "${outputHtmlPath}.plan.json"
        cp "${responsePath}"   "${outputHtmlPath}.response.json"

        echo "🔍 Recalculating guardrail coverage from HTML output"
        PASS_COUNT=\$(grep -oi 'class=\"pass\"' "${outputHtmlPath}" | wc -l)
        FAIL_COUNT=\$(grep -oi 'class=\"fail\"' "${outputHtmlPath}" | wc -l)
        TOTAL_COUNT=\$((PASS_COUNT + FAIL_COUNT))

        if [ "\$TOTAL_COUNT" -gt 0 ]; then
          COVERAGE=\$(awk "BEGIN {printf \\"%.0f\\", (\$PASS_COUNT/\$TOTAL_COUNT)*100}")
          sed -i "s/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: \$COVERAGE%/" "${outputHtmlPath}"
          echo "✅ Corrected coverage: \$COVERAGE%"
        else
          echo "⚠️ No rule evaluations found in HTML. Coverage not updated."
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
