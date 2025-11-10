def call(Map config = [:]) {
    def workdir = config.workdir
    def sharedLibDir = config.sharedLibDir
    def repoName = config.repoName
    def buildNumber = config.buildNumber
    def namespace = "${repoName}-${buildNumber}"

    stage("Index Terraform Code") {
        sh """
            echo '📦 Indexing Terraform code and guardrails into vector DB'
            python3 ${sharedLibDir}/indexer.py \
              --code_dir ${workdir}/terraform-infra-provision \
              --guardrails ${sharedLibDir}/guardrails_v1.txt \
              --namespace ${namespace}
        """
    }

    stage("AI Analytics") {
        sh """
            echo '🧠 Constructing payload for Azure OpenAI'
            python3 ${sharedLibDir}/query.py \
              --plan ${workdir}/tfplan.json \
              --guardrails ${sharedLibDir}/guardrails_v1.txt \
              --namespace ${namespace} \
              --output ${workdir}/payload.json

            echo '📡 Sending payload to Azure OpenAI'
            curl -s -X POST "${AZURE_API_BASE}/openai/deployments/text-embedding-ada-002/chat/completions?api-version=2023-05-15" \
              -H "Content-Type: application/json" \
              -H "api-key: ${AZURE_API_KEY}" \
              -d "@${workdir}/payload.json" \
              > ${workdir}/output.html.raw

            jq -r '.choices[0].message.content' ${workdir}/output.html.raw > ${workdir}/output.html

            echo '🧾 Logging normalized plan and raw response for audit'
            cp ${workdir}/tfplan.json ${workdir}/output.html.plan.json
            cp ${workdir}/output.html.raw ${workdir}/output.html.response.json
        """
    }

    stage("Evaluate Guardrail Coverage") {
        script {
            def coverage = sh(
                script: "grep -i 'Overall Guardrail Coverage' ${workdir}/output.html | grep -o '[0-9]\\{1,3\\}%'",
                returnStdout: true
            ).trim()

            echo "🛡️ Guardrail Coverage: ${coverage}"
        }
    }
}
