package com.locationshare.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.locationshare.client.api.ApiClient

class LoginActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "location_share"
        const val KEY_SERVER = "server_url"
        const val KEY_USER = "username"
        const val KEY_TOKEN = "auth_token"
        private const val DEFAULT_SERVER = "http://10.0.2.2:8080"

        fun saveSession(ctx: Context, server: String, username: String, token: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SERVER, server)
                .putString(KEY_USER, username)
                .putString(KEY_TOKEN, token)
                .apply()
            ApiClient.baseUrl = server
            ApiClient.token = token
        }

        fun clearSession(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_TOKEN)
                .apply()
            ApiClient.token = null
        }

        fun restoreSession(ctx: Context): Boolean {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val server = prefs.getString(KEY_SERVER, null) ?: return false
            val token = prefs.getString(KEY_TOKEN, null) ?: return false
            if (token.isBlank()) return false
            ApiClient.baseUrl = server
            ApiClient.token = token
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录则直接进入主页
        if (restoreSession(this)) {
            startActivity(Intent(this, DeviceListActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etUser = findViewById<EditText>(R.id.etUsername)
        val etPass = findViewById<EditText>(R.id.etPassword)
        val etRegKey = findViewById<EditText>(R.id.etRegKey)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        etServer.setText(prefs.getString(KEY_SERVER, DEFAULT_SERVER))
        etUser.setText(prefs.getString(KEY_USER, ""))

        fun applyServer(): String {
            var url = etServer.text.toString().trim().trimEnd('/')
            if (url.isEmpty()) url = DEFAULT_SERVER
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            ApiClient.baseUrl = url
            prefs.edit()
                .putString(KEY_SERVER, url)
                .putString(KEY_USER, etUser.text.toString().trim())
                .apply()
            etServer.setText(url)
            return url
        }

        btnLogin.setOnClickListener {
            val server = applyServer()
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "请输入账号密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiClient.login(u, p) { result ->
                runOnUiThread {
                    result.onSuccess { lr ->
                        saveSession(this, server, u, lr.token)
                        startActivity(Intent(this, DeviceListActivity::class.java))
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnRegister.setOnClickListener {
            val server = applyServer()
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            val key = etRegKey.text.toString().trim()
            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "请输入账号密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (key.isEmpty()) {
                Toast.makeText(this, "注册需要填写注册密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiClient.register(u, p, key) { result ->
                runOnUiThread {
                    result.onSuccess { lr ->
                        saveSession(this, server, u, lr.token)
                        Toast.makeText(this, "注册成功，已自动登录", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, DeviceListActivity::class.java))
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "注册失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
