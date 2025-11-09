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
        echo "📄 Escaping input files for payload"
        PLAN_FILE_CONTENT=\$(jq -Rs . < ${tfPlanJson})
        GUARDRAILS_CONTENT=\$(jq -Rs . < ${guardrailsPath})
        SAMPLE_HTML=\$(jq -Rs . < ${htmlTemplatePath})

        echo "📚 Retrieving RCA context from vector DB"
        RCA_CONTEXT=\$(python3 ${SHARED_LIB_DIR}/query.py \\
            --plan ${tfPlanJson} \\
            --namespace ${REPO_NAME}-${BUILD_NUMBER} | jq -Rs .)

        echo "🧠 Constructing payload for Azure OpenAI"
        cat <<EOF > ${payloadPath}
{
  "messages": [
    {
      "role": "system",
      "content": "You are a Terraform compliance auditor. You will receive: 1) Terraform Plan JSON, 2) Guardrails v1.0, 3) Sample HTML, and 4) RCA context from vector DB.\\n\\nYour task is to generate a single HTML report with:\\n- Change Summary Table\\n- Terraform Code Recommendations\\n- Security & Compliance Recommendations\\n- Guardrail Compliance Summary (with % calculation: passed/applicable × 100)\\n- Overall Status (PASS if ≥90%)\\n- RCA Suggestions from vector DB\\n- Match visual structure of sample HTML"
    },
    { "role": "user", "content": "Terraform Plan File:\\n" },
    { "role": "user", "content": \${PLAN_FILE_CONTENT} },
    { "role": "user", "content": "Sample HTML File:\\n" },
    { "role": "user", "content": \${SAMPLE_HTML} },
    { "role": "user", "content": "Guardrails Checklist File (Versioned):\\n" },
    { "role": "user", "content": \${GUARDRAILS_CONTENT} },
    { "role": "user", "content": "Similar RCA Context from Vector DB:\\n" },
    { "role": "user", "content": \${RCA_CONTEXT} }
  ],
  "max_tokens": 10000,
  "temperature": 0.0
}
EOF

        echo "📡 Sending payload to Azure OpenAI"
        RESPONSE_FILE=${outputHtmlPath}.raw
        curl -s -X POST "${azureApiBase}/openai/deployments/${deploymentName}/chat/completions?api-version=${apiVersion}" \\
             -H "Content-Type: application/json" \\
             -H "api-key: ${azureApiKey}" \\
             -d @${payloadPath} > \$RESPONSE_FILE

        echo "📥 Parsing response and writing output"
        if jq -e '.choices[0].message.content' \$RESPONSE_FILE > /dev/null; then
            jq -r '.choices[0].message.content' \$RESPONSE_FILE > ${outputHtmlPath}
        else
            echo "<html><body><h2>⚠️ AI response was empty or malformed</h2><p>Please check payload formatting and Azure OpenAI status.</p></body></html>" > ${outputHtmlPath}
        fi

        echo "🧾 Logging normalized plan and raw response for audit"
        cp ${tfPlanJson} ${outputHtmlPath}.plan.json
        cp \$RESPONSE_FILE ${outputHtmlPath}.response.json
    """
}
