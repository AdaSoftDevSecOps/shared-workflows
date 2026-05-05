/**
 * PHP Deployment Template (เหมือน GitHub Action: deploy-php-template-v2.yml)
 * 
 * วิธีเรียกใช้:
 * deployPhp(
 *     deployPath: 'C:/WebServer/Apache24/htdocs/MyProject',
 *     backupPath: 'C:/X-BACKUP/MyProject',
 *     excludeFiles: 'config_deploy.php',
 *     excludeFolders: 'Logs,uploads'
 * )
 */
def call(Map config = [:]) {
    def deployPath = config.deployPath
    def backupPath = config.backupPath
    def excludeFiles = config.excludeFiles ?: ""
    def excludeFolders = config.excludeFolders ?: ""
    def date = new Date().format('yyyyMMdd-HHmm', TimeZone.getTimeZone('Asia/Bangkok'))

    echo "🚀 Starting Deployment to ${deployPath}"

    powershell """
        # 1. สร้างโฟลเดอร์เตรียมไว้
        New-Item -ItemType Directory -Path "${deployPath}" -Force | Out-Null
        New-Item -ItemType Directory -Path "${backupPath}" -Force | Out-Null

        # 2. Backup โค้ดเก่า
        Write-Host "📦 Backing up old version..."
        $backupFile = Join-Path "${backupPath}" "backup-${date}.zip"
        if (Test-Path "${deployPath}/*") {
            & "C:/Program Files/7-Zip/7z.exe" a -tzip "$backupFile" "${deployPath}/*" -r
        }

        # 3. เตรียมไฟล์ที่จะ Preserve (เก็บไว้ไม่ให้ถูกทับ)
        $preserveTemp = Join-Path "${backupPath}" "TempPreserve"
        New-Item -ItemType Directory -Path "$preserveTemp" -Force | Out-Null
        
        # ตัวอย่าง logic การเก็บไฟล์ (สามารถปรับให้ dynamic ตาม config ได้)
        if (Test-Path "${deployPath}/config_deploy.php") {
            Copy-Item -Path "${deployPath}/config_deploy.php" -Destination "$preserveTemp" -Force
        }

        # 4. Copy ไฟล์ใหม่จาก Workspace ไปยัง Deploy Path (ยกเว้นตัวที่ไม่ต้องการ)
        Write-Host "🚚 Copying new files..."
        $excludeArray = "${excludeFiles},${excludeFolders}".Split(',')
        Get-ChildItem -Path "." -Exclude $excludeArray | Copy-Item -Destination "${deployPath}" -Recurse -Force

        # 5. Restore ไฟล์ที่เก็บไว้กลับมา
        Write-Host "♻️ Restoring preserved files..."
        if (Test-Path "$preserveTemp/config_deploy.php") {
            Copy-Item -Path "$preserveTemp/config_deploy.php" -Destination "${deployPath}" -Force
        }

        Write-Host "✅ Deployment Completed Successfully!"
    """
}
