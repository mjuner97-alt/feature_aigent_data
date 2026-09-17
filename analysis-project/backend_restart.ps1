$cl = (Get-Content 'D:/AILLMS/javacode/analysis-project/analysis-project/backend_restart_cmd.txt' -Raw).Trim()
$exe, $args = $cl -split ' ', 2
Start-Process -FilePath $exe -ArgumentList $args -WorkingDirectory 'D:\AILLMS\javacode\analysis-project\analysis-project' -RedirectStandardOutput 'D:\AILLMS\javacode\analysis-project\analysis-project\backend_restart.log' -RedirectStandardError 'D:\AILLMS\javacode\analysis-project\analysis-project\backend_restart.err.log' -WindowStyle Hidden
Write-Output 'STARTED'
