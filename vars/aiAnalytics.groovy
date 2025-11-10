def call(Map config = [:]) {
    def workdir = config.workdir
    def sharedLibDir = config.sharedLibDir
    def repoName = config.repoName
    def buildNumber = config.buildNumber
    def namespace = "${repoName}-${buildNumber}"

    stage("AI Analytics") {
        withEnv(["VENV_PATH=venv"]) {

            sh """
                echo '🔥 Cleaning Python caches and old virtualenv'
                find ${sharedLibDir} -name '*.pyc' -delete
                find ${sharedLibDir} -name '__pycache__' -type d -exec rm -rf {} +
                rm -rf \$VENV_PATH

                echo '🐍 Creating fresh Python virtual environment'
                python3 -m venv \$VENV_PATH
                . \$VENV_PATH/bin/activate
                pip install --upgrade pip

                echo '📦 Installing required packages one by one'
                pip install langchain==0.0.300
                pip install langchain-openai==0.0.5
                pip install chromadb==0.4.22

                echo '🧾 Logging installed packages for audit'
                pip freeze > ${workdir}/installed_packages.txt

                echo '🔍 Verifying correct indexer.py is in use'
                grep 'from langchain' ${sharedLibDir}/indexer.py | head -n 3
            """

            sh """
                echo '📦 Indexing Terraform code and guardrails into vector DB'
                . \$VENV_PATH/bin/activate
                python3 ${sharedLibDir}/indexer.py \
                  --code_dir ${workdir}/terraform-infra-provision \
                  --guardrails ${sharedLibDir}/guardrails_v1.txt \
                  --namespace ${namespace}
            """

            sh """
                echo '🧠 Constructing payload for Azure OpenAI'
                . \$VENV_PATH/bin/activate
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

            sh """
                echo '🛡️ Extracting Guardrail Coverage'
                grep -i 'Overall Guardrail Coverage' ${workdir}/output.html | grep -o '[0-9]\\{1,3\\}%'
            """
        }
    }
}
