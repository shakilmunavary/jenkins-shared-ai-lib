// vars/setupPythonEnv.groovy

def call(Map config = [:]) {
    def sharedLibDir = config.sharedLibDir ?: "jenkins-shared-ai-lib"

    sh """
        echo '🐍 Creating virtual environment and installing dependencies'
        python3 -m venv venv
        . venv/bin/activate
        pip install --upgrade pip
        pip install -r ${sharedLibDir}/requirements.txt
    """
}
