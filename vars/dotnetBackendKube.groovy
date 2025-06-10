def call(Map config = [:]) {
    def repository = config.repository
    def environment = env.BRANCH_NAME.replaceAll('/', '-')
    def aspnetcoreEnvironment = (env.BRANCH_NAME == 'uat' || env.BRANCH_NAME == 'master') ? 'Production' : 'Development'
    def kubernetesNamespace = 'dotnet-backend'
    def nodePort = config.nodePortMap[env.BRANCH_NAME]
    if (!nodePort) {
        error "NodePort not found for branch: ${env.BRANCH_NAME}. Available branches: ${config.nodePortMap.keySet()}"
    }

    def tag = "1.0.${BUILD_NUMBER}-${environment}"
    def registryUrl = "192.168.1.88:5000"
    def imageTag = "${registryUrl}/${repository}:${tag}"
    def dotnetBackendPath = 'dotnet-backend'
    
    pipeline {
        agent {
            label 'Ubuntu-agent'
        }
        
        stages {
            stage('Checkout') {
                steps {
                    script {
                        echo "Checking out branch ${env.BRANCH_NAME} from main repository"
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: env.BRANCH_NAME]],
                            userRemoteConfigs: [[url: env.GIT_URL, credentialsId: 'jenkins-with-credential']]
                        ])


                        // Copy required files from shared pipeline resources
                        def dockerfilePath = 'Dockerfile'
                        def deploymentFilePath = 'application.deployment.yaml'
                        
                        copyResourceFile(dotnetBackendPath, dockerfilePath)
                        copyResourceFile(dotnetBackendPath, deploymentFilePath)
                    }
                }
            }
            
            stage('Build') {
                steps {
                    script {
                        if (!config.projectName) {
                            error "Required parameter 'projectName' not provided"
                        }
                        if (!config.projectDllName) {
                            error "Required parameter 'projectDllName' not provided"
                        }

                        def projectName = config.projectName
                        def projectDllName = config.projectDllName

                        echo "Building with parameters:"
                        echo "Project Name: ${projectName}"
                        echo "Project DLL: ${projectDllName}" 
                        echo "Image tag: ${imageTag}"
                        echo "Branch: ${env.BRANCH_NAME}"
                        echo "Kubernetes Namespace: ${kubernetesNamespace}"
                        echo "Deployment Name: ${environment}-${repository}"
                        echo "ASPNETCORE_ENVIRONMENT: ${aspnetcoreEnvironment}"

                        sh "docker buildx build --platform linux/amd64 --no-cache -t ${imageTag} --build-arg BUILD_CONFIGURATION=Release --build-arg PROJECT_NAME=${projectName} --build-arg PROJECT_DLL_NAME=${projectDllName} ."

                        echo "Pushing image to registry: ${imageTag}"
                        sh "docker push ${imageTag}"
                        sh "docker rmi ${imageTag} || true"
                    }
                }
            }
            
            stage('Deployment') {
                steps {
                    script {
                        echo "Deploying to Kubernetes: ${repository} in ${environment} environment"
                        echo "Setting ASPNETCORE_ENVIRONMENT to: ${aspnetcoreEnvironment}"
                        
                        // Create a temporary deployment file with environment variables replaced
                        sh """
                        cat application.deployment.yaml | \
                        sed 's|\\\${ENVIRONMENT}|${environment}|g' | \
                        sed 's|\\\${REPOSITORY}|${repository}|g' | \
                        sed 's|\\\${IMAGE_TAG}|${imageTag}|g' | \
                        sed 's|\\\${NODEPORT}|${nodePort}|g' | \
                        sed 's|\\\${NAMESPACE}|${kubernetesNamespace}|g' > deployment.yaml
                        sed -i 's/value: "${environment}"/value: "${aspnetcoreEnvironment}"/g' deployment.yaml
                        """
                        
                        sh "kubectl apply -f deployment.yaml"
                    }
                }
            }
        }

        post {
            always {
                script {
                    try {
                        echo "Cleaning up Docker image: ${imageTag}"
                        sh "docker rmi ${imageTag} || true"
                        sh "rm -f deployment.yaml || true"
                    } catch (Exception e) {
                        echo "Failed to clean up: ${e.message}"
                    }
                    
                    deleteDir()
                    echo "Workspace has been cleaned up"
                }
            }
            success {
                echo "Pipeline executed successfully"
            }
            failure {
                echo "Pipeline execution failed"
            }
        }
    }
}

def copyResourceFile(resourcePath, fileName) {
    try {
        echo "Copying ${fileName} from shared pipeline library..."
        
        def fullResourcePath = "${resourcePath}/${fileName}"
        def fileContent = libraryResource(fullResourcePath)
        writeFile file: fileName, text: fileContent
        
        echo "✓ ${fileName} copied successfully from ${fullResourcePath}"
        
    } catch (Exception e) {
        error "Failed to copy ${fileName}: ${e.message}"
    }
}