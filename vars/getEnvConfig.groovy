/**
 * Centralized Environment Configuration for Shared Library
 * Usage: def config = getEnvConfig('DEV')
 */
def call(String envName) {
    def envs = [
        'DEV': [
            host         : '192.168.0.126',
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key',
            sqlHost      : '27.254.239.245',
            sqlPort      : '33433',
            dbCredId     : 'sql-dev-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'C:/X-BACKUP/SQL',
            baseDeployPath: 'C:/WebServer/Apache24/htdocs',
            baseBackupPath: 'C:/X-BACKUP/AdaStoreBack'
        ],
        'SIT': [
            host         : '192.168.0.134',
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key',
            sqlHost      : '27.254.239.244',
            sqlPort      : '33435',
            dbCredId     : 'sql-sit-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'C:/X-BACKUP/SQL',
            baseDeployPath: 'C:/WebServer/Apache24/htdocs',
            baseBackupPath: 'C:/X-BACKUP/AdaStoreBack'
        ],
        'UAT': [
            host         : '192.168.0.127',
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key',
            sqlHost      : '27.254.239.246',
            sqlPort      : '49833',
            dbCredId     : 'sql-uat-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'D:/X-BACKUP/SQL',
            baseDeployPath: 'D:/WebServer/Apache24/htdocs',
            baseBackupPath: 'D:/X-BACKUP/AdaStoreBack'
        ],
        'PROD': [
            host         : '192.168.0.200',
            user         : 'Administrator',
            sshCredId    : 'windows-ssh-key',
            sqlHost      : '27.254.239.245',
            sqlPort      : '33433',
            dbCredId     : 'sql-prod-creds',
            gitCredId    : 'AdaDevSecOps',
            sqlBackupPath: 'E:/X-BACKUP/SQL',
            baseDeployPath: 'E:/App/Production',
            baseBackupPath: 'E:/X-BACKUP/Production'
        ]
    ]

    def config = envs[envName.toUpperCase()]
    if (!config) {
        error "❌ Environment '${envName}' not found in getEnvConfig.groovy. Available: ${envs.keySet()}"
    }
    return config
}
