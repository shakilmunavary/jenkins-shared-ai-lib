def call(String tfStatePath) {
    echo "🧠 Running AI analytics on ${tfStatePath}"

    def scriptPath = "${libraryResource('aiAnalytics/analyzeTfState.py')}"
    writeFile file: 'analyzeTfState.py', text: scriptPath

    sh """
        python3 analyzeTfState.py ${tfStatePath}
    """
}
