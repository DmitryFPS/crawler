#!/usr/bin/env pwsh
param(
    [switch]$Fix
)

Write-Host "🔍 Диагностика crawler-platform" -ForegroundColor Cyan

# 1. Контейнеры
Write-Host "`n📦 Контейнеры:" -ForegroundColor Yellow
docker ps -a --filter "name=crawler" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 2. Сеть
Write-Host "`n🌐 Сеть:" -ForegroundColor Yellow
docker network inspect crawler-platform_default --format "{{range .Containers}}{{.Name}} {{end}}" 2>$null

# 3. Selenium статус
Write-Host "`n🤖 Selenium Hub:" -ForegroundColor Yellow
try {
    $resp = Invoke-RestMethod -Uri "http://localhost:4444/wd/hub/status" -UseBasicParsing -TimeoutSec 5
    Write-Host "  Ready: $($resp.value.ready)" -ForegroundColor $(if($resp.value.ready){"Green"}else{"Red"})
} catch {
    Write-Host "  ❌ Не доступен: $($_.Exception.Message)" -ForegroundColor Red
}

# 4. App health
Write-Host "`n🏥 App Health:" -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 5
    Write-Host "  Status: $($health.status)" -ForegroundColor $(if($health.status -eq "UP"){"Green"}else{"Red"})
} catch {
    Write-Host "  ❌ Не доступен: $($_.Exception.Message)" -ForegroundColor Red
}

# 5. Последние ошибки
Write-Host "`n⚠️  Последние ошибки в логах:" -ForegroundColor Yellow
docker logs crawler-app --tail 50 2>&1 | Select-String "error|exception|timeout" -CaseSensitive:$false | Select-Object -First 10

# 6. Если --Fix — перезапустить Selenium
if ($Fix) {
    Write-Host "`n🔧 Перезапуск Selenium..." -ForegroundColor Cyan
    docker-compose restart selenium
    Start-Sleep -Seconds 5
    Write-Host "✅ Готово" -ForegroundColor Green
}