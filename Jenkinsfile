pipeline {
    agent any

    stages {
        stage('build') {
            steps {
                echo 'building a jar'
                sh 'boot build-jar'
            }
        }
        stage('deploy') {
            steps {
                echo 'deploying a snapshot'
                sh 'boot push-snapshot'
            }
        }
    }
}
