package com.dianguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrashSaver 脱敏回归测试（v1.4.3 安全增强）。
 *
 * 覆盖三类必须脱敏的信息通道：
 *  ① 精确坐标（home_lat / home_lon / latitude / longitude / 地址）
 *  ② API Key 键值对（token= / apiKey= / secret / password）
 *  ③ 完整 WebSocket URL（wss://host/path?token=XXXX，OkHttp 异常消息常见形态）
 */
class CrashSaverTest {

    @Test fun redactCoordinatesFields() {
        val raw = "home_lat=25.0452 home_lon=102.7098 location_name=云南昆明 address=五华区"
        val out = CrashSaver.redactCoordinates(raw)
        assertFalse("精确坐标必须脱敏", out.contains("25.0452"))
        assertFalse("经度必须脱敏", out.contains("102.7098"))
        assertFalse("地址必须脱敏", out.contains("五华区"))
        assertTrue(out.contains("home_lat=[REDACTED]"))
        assertTrue(out.contains("address=[REDACTED]"))
    }

    @Test fun redactTokenKeyValue() {
        val raw = "beecld token=sk-abc123DEF456 连接失败"
        val out = CrashSaver.redactCoordinates(raw)
        assertFalse("token 值必须脱敏", out.contains("sk-abc123DEF456"))
        assertTrue(out.contains("token=[REDACTED]"))
    }

    @Test fun redactWsUrlToken() {
        val raw = "failed to connect wss://api.2v8.cn/ws/cea?token=sk-abc123DEF456 some error"
        val out = CrashSaver.redactCoordinates(raw)
        assertFalse("URL 内 token 必须脱敏", out.contains("sk-abc123DEF456"))
        assertFalse("URL 内 token 键名不应残留明文值", out.contains("token=sk"))
        // URL 骨架保留（便于排查域名），值被收敛
        assertTrue(out.contains("wss://api.2v8.cn/ws/cea?token=[REDACTED]"))
    }

    @Test fun redactApiKeyAndPassword() {
        val raw = "apiKey=GHI789 password=p@ssw0rd123"
        val out = CrashSaver.redactCoordinates(raw)
        assertFalse(out.contains("GHI789"))
        assertFalse(out.contains("p@ssw0rd123"))
        assertTrue(out.contains("apiKey=[REDACTED]"))
        assertTrue(out.contains("password=[REDACTED]"))
    }

    @Test fun plainTextUntouched() {
        // 不包含敏感键名的普通日志保持原文，避免误伤排查信息
        val raw = "EewService started ok, source=cenc report=3"
        assertEquals(raw, CrashSaver.redactCoordinates(raw))
    }
}