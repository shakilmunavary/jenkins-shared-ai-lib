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

        echo "🧠 Constructing deterministic prompt for Azure OpenAI"
        cat <<EOF > ${payloadPath}
{
  "messages": [
    {
      "role": "system",
    "content": "You are a Terraform compliance auditor. You will receive three input files: 1) Terraform Plan in JSON format, 2) Guardrails Checklist (versioned), and 3) Sample HTML Template.\\n\\nYour task is to analyze the Terraform plan against the guardrails and return a single HTML output with the following sections:\\n\\n1️⃣ Change Summary Table\\n- Title: 'What's Being Changed'\\n- Columns: Resource Name, Resource Type, Action (Add/Delete/Update), Details\\n- Ensure resource count matches Terraform plan\\n\\n2️⃣ Terraform Code Recommendations\\n- Actionable suggestions to improve code quality\\n\\n3️⃣ Security and Compliance Recommendations\\n- Highlight misconfigurations and generic recommendations\\n\\n4️⃣ Guardrail Coverage Table\\n- Title: 'Guardrail Compliance Summary'\\n- Columns: Terraform Resource, Rule Id, Rule, Status (PASS, FAIL)\\n- End with Overall Guardrail Coverage %\\n\\n📊 To generat the Guardrail Coverage Table take every single rule from guradrails text file and compare it againt the teraform resource from terraform plan. If the rule is compliant keep the staus column as PASS else fail. Calculate Overall pass percentage from the total rules compared.\\n\\n5️⃣ Overall Status\\n- Status: PASS if coverage ≥ 90%, else FAIL\\n\\n6️⃣ HTML Formatting\\n- Match visual structure of sample HTML using semantic tags and inline styles"
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

        echo "🔍 Recalculating guardrail coverage from HTML output"
        PASS_COUNT=\$(grep -o 'class="pass"' ${outputHtmlPath} | wc -l)
        FAIL_COUNT=\$(grep -o 'class="fail"' ${outputHtmlPath} | wc -l)
        TOTAL_COUNT=\$((PASS_COUNT + FAIL_COUNT))

        if [ \$TOTAL_COUNT -gt 0 ]; then
            COVERAGE=\$(awk "BEGIN {printf \\"%.0f\\", (\$PASS_COUNT/\$TOTAL_COUNT)*100}")
            sed -i "s/Overall Guardrail Coverage: .*/Overall Guardrail Coverage: \$COVERAGE%/" ${outputHtmlPath}
            echo "✅ Corrected coverage: \$COVERAGE%"
        else
            echo "⚠️ No rule evaluations found in HTML. Coverage not updated."
        fi
    """
}
