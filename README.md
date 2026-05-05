# Ada Shared Library (Jenkins Shared Workflows)

คลังรวบรวมฟังก์ชันมาตรฐาน (Shared Library) สำหรับการทำ CI/CD ของโปรเจกต์ PHP และ SQL Server โดยใช้ Jenkins เป็นตัวควบคุมหลัก

## 🏗️ Architecture (สถาปัตยกรรม)

ระบบถูกออกแบบมาให้ทำงานแบบ Hybrid:
1.  **Jenkins (Linux)**: ทำหน้าที่เป็นศูนย์กลางดึงโค้ด (Git), แพ็กไฟล์ (Zip) และสั่งรันงาน
2.  **SQL Server (Windows)**: รับคำสั่ง `sqlcmd` โดยตรงจาก Jenkins Linux เพื่อรันสคริปต์ Database
3.  **App Server (Windows)**: รับไฟล์โปรเจกต์ผ่าน `SCP` และรันคำสั่งจัดการไฟล์ผ่าน `SSH`

---

## 🛠️ Library Components

### 1. `sqlExecute.groovy` (Database Deployment)
ใช้สำหรับรันสคริปต์ SQL แบบอัตโนมัติ โดยมีลำดับขั้นตอนดังนี้:
*   **Checkout**: ดึงสคริปต์จาก `AdaScriptCenter` มาไว้ที่เครื่อง Jenkins
*   **Verify**: ตรวจสอบการเชื่อมต่อกับ SQL Server ก่อนเริ่มงาน
*   **Backup**: สั่ง Backup Database ปัจจุบันไว้ที่เครื่อง SQL Server (ระบุพาธได้)
*   **Ordered Execution**: รันสคริปต์ตามลำดับที่ถูกต้อง (Structure -> Stored -> Data)
*   **UTF-8 Support**: รองรับภาษาไทยด้วยพารามิเตอร์ `-f 65001`

### 2. `deployPhp.groovy` (PHP App Deployment)
ใช้สำหรับการ Deploy Source Code ไปยัง Windows Server โดยมีคุณสมบัติเด่นคือ:
*   **Packaging**: บีบอัดไฟล์โดยตัดโฟลเดอร์ที่ไม่จำเป็น (Exclude) เช่น `.git`, `Logs`
*   **Preservation**: มีระบบดึงไฟล์สำคัญ (เช่น `config_deploy.php`, `uploads`) ออกมาเก็บไว้ก่อน และนำกลับไปวางคืนหลัง Deploy เสร็จ เพื่อไม่ให้ข้อมูลหาย
*   **Current Version Backup**: สำรองข้อมูล Code เวอร์ชันเดิมก่อนทับใหม่ เพื่อให้ Rollback ได้ทันที
*   **Retention Policy**: ระบบลบไฟล์ Backup เก่าอัตโนมัติ (Keep Count) เพื่อประหยัดพื้นที่ Harddisk

---

## 📝 วิธีใช้งานใน Jenkinsfile

คุณสามารถนำไปใช้ในโปรเจกต์ใหม่ได้ง่ายๆ โดยการตั้งค่าในบล็อก `environment`:

```groovy
@Library('ada-shared-lib') _

pipeline {
    agent any
    environment {
        // --- ข้อมูล Server ---
        REMOTE_HOST      = "192.168.0.xxx"
        SQL_HOST         = "192.168.0.yyy"
        
        // --- ข้อมูล Path ---
        DEPLOY_PATH       = "C:/WebServer/Apache24/htdocs/YourProject"
        BACKUP_PATH       = "C:/X-BACKUP/YourProject"
        
        // --- ข้อมูล SQL ---
        DB_NAME           = "YourDBName"
        DB_CRED_ID        = "sql-creds-id"
    }
    stages {
        stage('Execute SQL') {
            steps {
                sqlExecute(host: env.REMOTE_HOST, sqlHost: env.SQL_HOST, dbName: env.DB_NAME, ...)
            }
        }
        stage('Deploy PHP') {
            steps {
                deployPhp(host: env.REMOTE_HOST, deployPath: env.DEPLOY_PATH, ...)
            }
        }
    }
}
```

---

## ⚙️ Requirements (สิ่งที่ต้องเตรียม)

1.  **Jenkins Linux Server**: 
    *   ติดตั้ง `mssql-tools18` (สำหรับการใช้ `sqlcmd`)
    *   ติดตั้ง `zip`, `ssh`, `scp`
2.  **Windows Server (Target)**:
    *   เปิดใช้งาน `OpenSSH Server`
    *   ติดตั้ง `7-Zip` (สำหรับใช้แตกไฟล์)
3.  **Credentials**:
    *   สร้าง ID ใน Jenkins สำหรับ SSH และ Database ให้เรียบร้อย