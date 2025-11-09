def call(
    String tfPlanJson,
    String guardrailsPath,
    String htmlTemplatePath,
    String outputHtmlPath,
    String payloadPath,
    String azureApiKey,
    String azureApiBase,
    String deploymentName,
    String apiVersion
) {
    sh """
        PLAN_FILE_CONTENT=\$(jq -Rs . < ${tfPlanJson})
        GUARDRAILS_CONTENT=\$(jq -Rs . < ${guardrailsPath})
        SAMPLE_HTML=\$(jq -Rs . < ${htmlTemplatePath})

        cat <<EOF > ${payloadPath}
{
  "messages": [
    {
      "role": "system",
      "content": "You are a Terraform compliance expert. You will receive three input files: 1) Terraform Plan in JSON format, 2) Guardrails Checklist in structured text format, and 3) Sample HTML Template for visual formatting reference.\\n\\nYour task is to analyze the Terraform plan against the guardrails and return a single HTML output that includes the following five sections:\\n\\n1️⃣ Change Summary Table\\n- Title: 'What's Being Changed'\\n- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details. Ensure All the resources being added, changed, destroyed are added to summary table and the count in terraform plan and summary table should match.\\n\\n2️⃣ Terraform Code Recommendations\\n- Actionable suggestions to improve code quality and maintainability\\n\\n3️⃣ Security and Compliance Recommendations\\n- Highlight misconfigurations and generic recomendations.\\n\\n4️⃣ Guardrail Coverage Table\\n- Title: 'Guardrail Compliance Summary'\\n- Columns: Terraform Resource, Guardrail Type (e.g., EC2, S3), Non Compliance Rules (Provide Rule Id and Rule details in the rows for this column), Percentage Met\\n- End with Overall Guardrail Coverage %\\n\\n5️⃣ Overall Status\\n- Status: PASS if coverage ≥ 90%, else FAIL\\n\\n6️⃣ HTML Formatting\\n- Match the visual structure and styling of the provided sample HTML file using semantic tags and inline styles"
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": \${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File:\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        curl -s -X POST "${azureApiBase}/openai/deployments/${deploymentName}/chat/completions?api-version=${apiVersion}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: ${azureApiKey}" \\
             -d @${payloadPath} > ${outputHtmlPath}

        jq -r '.choices[0].message.content' ${outputHtmlPath} > ${outputHtmlPath}
    """
}
