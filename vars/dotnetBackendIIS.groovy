def call(Map config = [:]) {
  def repository = config.repository
    def environment = env.BRANCH_NAME.replaceAll('/', '-')
    def aspnetcoreEnvironment = (env.BRANCH_NAME == 'uat') ? 'Production' : 'Development'
    def iisPort = config.iisPortMap[env.BRANCH_NAME]
    if (!iisPort) {
        error "IIS Port not found for branch: ${env.BRANCH_NAME}. Available branches: ${config.iisPortMap.keySet()}"
    }

    def siteName = "${environment}-${repository}"
    def appPath = "C:\\inetpub\\wwwroot\\${siteName}"
    def dotnetBackendPath = 'dotnet-backend'

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

            // Copy required files from shared pipeline resources if needed
            echo "Repository checked out successfully"
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
            echo "Branch: ${env.BRANCH_NAME}"
            echo "Site Name: ${siteName}"
            echo "IIS Port: ${iisPort}"
            echo "App Path: ${appPath}"
            echo "ASPNETCORE_ENVIRONMENT: ${aspnetcoreEnvironment}"

            // Clean previous build
            bat 'if exist publish rmdir /s /q publish'
            
            // Restore, build and publish
            bat "dotnet restore ${projectName}"
            bat "dotnet build ${projectName} --configuration Release --no-restore"
            bat "dotnet publish ${projectName} --configuration Release --output .\\publish --no-build"
            
            echo "✓ Application built and published successfully"
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
      \$projectDllName = "${config.projectDllName}"
      \$aspnetcoreEnv = "${aspnetcoreEnvironment}"

      # Define folders to preserve
      \$foldersToPreserve = @("wwwroot")
      \$backupPath = "\$env:TEMP\\backup_\$siteName"

      Write-Host "Deploying to IIS Site: \$siteName"
      Write-Host "Path: \$appPath"
      Write-Host "Port: \$port"
      Write-Host "ASPNETCORE_ENVIRONMENT: \$aspnetcoreEnv"

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
        
        # Create Application Pool
        Write-Host "Creating application pool: \$siteName"
        New-WebAppPool -Name \$siteName -Force
        Set-ItemProperty -Path "IIS:\\AppPools\\\$siteName" -Name processModel.identityType -Value ApplicationPoolIdentity
        Set-ItemProperty -Path "IIS:\\AppPools\\\$siteName" -Name managedRuntimeVersion -Value ""
        
        # Backup folders to preserve
        if (Test-Path \$appPath) {
          Write-Host "Backing up folders to preserve..."
          
          # Create backup directory
          if (Test-Path \$backupPath) {
            Remove-Item \$backupPath -Recurse -Force
          }
          New-Item -Path \$backupPath -ItemType Directory -Force
          
          # Backup each folder that needs to be preserved
          foreach (\$folder in \$foldersToPreserve) {
            \$sourcePath = Join-Path \$appPath \$folder
            \$destinationPath = Join-Path \$backupPath \$folder
            
            if (Test-Path \$sourcePath) {
              Write-Host "Backing up: \$folder"
              Copy-Item \$sourcePath \$destinationPath -Recurse -Force
            } else {
              Write-Host "Folder not found, skipping backup: \$folder"
            }
          }
        }
        
        # Create/Clean Website Directory
        if (Test-Path \$appPath) {
          Write-Host "Cleaning existing directory: \$appPath"
          Remove-Item \$appPath -Recurse -Force
        }
        New-Item -Path \$appPath -ItemType Directory -Force
        
        # Copy published files
        Write-Host "Copying published files to \$appPath"
        Copy-Item ".\\publish\\*" \$appPath -Recurse -Force
        
        # Restore backed up folders
        if (Test-Path \$backupPath) {
          Write-Host "Restoring preserved folders..."
          
          foreach (\$folder in \$foldersToPreserve) {
            \$sourcePath = Join-Path \$backupPath \$folder
            \$destinationPath = Join-Path \$appPath \$folder
            
            if (Test-Path \$sourcePath) {
              Write-Host "Restoring: \$folder"
              Copy-Item \$sourcePath \$destinationPath -Recurse -Force
            }
          }
          
          # Clean up backup
          Write-Host "Cleaning up backup directory"
          Remove-Item \$backupPath -Recurse -Force
        }
        
        # Create web.config using single quotes to avoid Here-String issues
        \$webConfigContent = '<?xml version="1.0" encoding="utf-8"?><configuration><location path="." inheritInChildApplications="false"><system.webServer><handlers><add name="aspNetCore" path="*" verb="*" modules="AspNetCoreModuleV2" resourceType="Unspecified" /></handlers><aspNetCore processPath="dotnet" arguments=".\\' + \$projectDllName + '" stdoutLogEnabled="true" stdoutLogFile=".\\logs\\stdout" hostingModel="inprocess"><environmentVariables><environmentVariable name="ASPNETCORE_ENVIRONMENT" value="' + \$aspnetcoreEnv + '" /></environmentVariables></aspNetCore></system.webServer></location></configuration>'
        
        Write-Host "Creating web.config"
        \$webConfigContent | Out-File -FilePath "\$appPath\\web.config" -Encoding UTF8
        
        # Create logs directory
        New-Item -Path "\$appPath\\logs" -ItemType Directory -Force
        
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
        Write-Host "✓ Preserved folders: \$(\$foldersToPreserve -join ', ')"
        
      } catch {
        Write-Error "Failed to deploy to IIS: \$_"
        
        # Clean up backup if something goes wrong
        if (Test-Path \$backupPath) {
          Remove-Item \$backupPath -Recurse -Force -ErrorAction SilentlyContinue
        }
        
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
              bat 'if exist publish rmdir /s /q publish || echo "No publish directory to clean"'
              bat 'if exist bin rmdir /s /q bin || echo "No bin directory to clean"'
              bat 'if exist obj rmdir /s /q obj || echo "No obj directory to clean"'
            } catch (Exception e) {
              echo "Failed to clean up: ${e.message}"
            }
            
            echo "Workspace cleanup completed"
        }
      }
      success {
        script {
          echo "✓ Pipeline executed successfully"
          echo "✓ Application deployed to IIS site: ${siteName}"
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