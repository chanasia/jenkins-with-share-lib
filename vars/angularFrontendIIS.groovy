def call(Map config = [:]) {
    def repository = config.repository
    def environment = env.BRANCH_NAME.replaceAll('/', '-')
    def buildConfiguration = (env.BRANCH_NAME == 'uat') ? 'uat' : (env.BRANCH_NAME == 'master') ? 'production' : 'development'
    def iisPort = config.iisPortMap[env.BRANCH_NAME]
    if (!iisPort) {
        error "IIS Port not found for branch: ${env.BRANCH_NAME}. Available branches: ${config.iisPortMap.keySet()}"
    }

    def siteName = "${environment}-${repository}"
    def appPath = "C:\\inetpub\\wwwroot\\${siteName}"
    
    pipeline {
        agent {
            label 'Windows-agent'
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

                        echo "Repository checked out successfully"
                    }
                }
            }
            
            stage('Build') {
                steps {
                    script {
                        echo "Building with parameters:"
                        echo "Repository: ${repository}"
                        echo "Branch: ${env.BRANCH_NAME}"
                        echo "Build Configuration: ${buildConfiguration}"
                        echo "Site Name: ${siteName}"
                        echo "IIS Port: ${iisPort}"
                        echo "App Path: ${appPath}"

                        // Clean previous build
                        bat 'if exist dist rmdir /s /q dist'
                        bat 'if exist node_modules rmdir /s /q node_modules'
                        
                        // Install dependencies
                        echo "Installing npm dependencies..."
                        bat 'npm install'
                        
                        // Build Angular application
                        echo "Building Angular application for ${buildConfiguration}..."
                        bat "ng build --configuration=${buildConfiguration} --output-path=dist"
                        
                        echo "✓ Angular application built successfully"
                    }
                }
            }
            
            stage('Deploy to IIS') {
                steps {
                    script {
                        echo "Deploying ${siteName} to IIS on port ${iisPort}"
                        
                        powershell """
                        Import-Module WebAdministration
                        
                        \$siteName = "${siteName}"
                        \$appPath = "${appPath}"
                        \$port = ${iisPort}
                        \$buildConfig = "${buildConfiguration}"
                        
                        Write-Host "Deploying to IIS Site: \$siteName"
                        Write-Host "Path: \$appPath"
                        Write-Host "Port: \$port"
                        Write-Host "Build Configuration: \$buildConfig"
                        
                        try {
                            # Stop existing website if running
                            if (Get-Website -Name \$siteName -ErrorAction SilentlyContinue) {
                                Write-Host "Stopping existing website: \$siteName"
                                Stop-Website -Name \$siteName -ErrorAction SilentlyContinue
                            }
                            
                            # Remove existing Application Pool
                            if (Get-IISAppPool -Name \$siteName -ErrorAction SilentlyContinue) {
                                Write-Host "Removing existing application pool: \$siteName"
                                Remove-WebAppPool -Name \$siteName -ErrorAction SilentlyContinue
                                Start-Sleep -Seconds 2
                            }
                            
                            # Create Application Pool for Static Files
                            Write-Host "Creating application pool: \$siteName"
                            New-WebAppPool -Name \$siteName -Force
                            Set-ItemProperty -Path "IIS:\\AppPools\\\$siteName" -Name processModel.identityType -Value ApplicationPoolIdentity
                            Set-ItemProperty -Path "IIS:\\AppPools\\\$siteName" -Name managedRuntimeVersion -Value ""
                            
                            # Create/Clean Website Directory
                            if (Test-Path \$appPath) {
                                Write-Host "Cleaning existing directory: \$appPath"
                                Remove-Item \$appPath -Recurse -Force
                            }
                            New-Item -Path \$appPath -ItemType Directory -Force
                            
                            # Copy built Angular files
                            Write-Host "Copying Angular build files to \$appPath"
                            if (Test-Path "dist") {
                                Copy-Item "dist\\*" \$appPath -Recurse -Force
                            } else {
                                throw "Build output directory 'dist' not found"
                            }
                            
                            # Create web.config for Angular SPA
                            \$webConfig = @"
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <system.webServer>
    <rewrite>
      <rules>
        <rule name="Angular Routes" stopProcessing="true">
          <match url=".*" />
          <conditions logicalGrouping="MatchAll">
            <add input="{REQUEST_FILENAME}" matchType="IsFile" negate="true" />
            <add input="{REQUEST_FILENAME}" matchType="IsDirectory" negate="true" />
          </conditions>
          <action type="Rewrite" url="/index.html" />
        </rule>
      </rules>
    </rewrite>
    <staticContent>
      <mimeMap fileExtension=".json" mimeType="application/json" />
      <mimeMap fileExtension=".woff" mimeType="application/font-woff" />
      <mimeMap fileExtension=".woff2" mimeType="application/font-woff2" />
    </staticContent>
    <httpErrors existingResponse="PassThrough" />
  </system.webServer>
</configuration>
"@
                            Write-Host "Creating web.config for Angular SPA"
                            \$webConfig | Out-File -FilePath "\$appPath\\web.config" -Encoding UTF8
                            
                            # Remove existing website
                            if (Get-Website -Name \$siteName -ErrorAction SilentlyContinue) {
                                Remove-Website -Name \$siteName
                            }
                            
                            # Create Website
                            Write-Host "Creating website: \$siteName on port \$port"
                            New-Website -Name \$siteName -Port \$port -PhysicalPath \$appPath -ApplicationPool \$siteName
                            
                            # Start Website
                            Start-Website -Name \$siteName
                            
                            Write-Host "✓ Website \$siteName deployed successfully"
                            Write-Host "✓ Application URL: http://localhost:\$port"
                            
                        } catch {
                            Write-Error "Failed to deploy to IIS: \$_"
                            throw \$_
                        }
                        """
                    }
                }
            }
        }

        post {
            always {
                script {
                    try {
                        echo "Cleaning up build artifacts"
                        bat 'if exist dist rmdir /s /q dist || echo "No dist directory to clean"'
                        bat 'if exist node_modules rmdir /s /q node_modules || echo "No node_modules directory to clean"'
                    } catch (Exception e) {
                        echo "Failed to clean up: ${e.message}"
                    }
                    
                    echo "Workspace cleanup completed"
                }
            }
            success {
                script {
                    echo "✓ Pipeline executed successfully"
                    echo "✓ Angular application deployed to IIS site: ${siteName}"
                    echo "✓ Application URL: http://your-windows-server:${iisPort}"
                }
            }
            failure {
                script {
                    echo "✗ Pipeline execution failed"
                    echo "Please check the logs above for error details"
                }
            }
        }
    }
}