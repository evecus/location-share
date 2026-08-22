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
        private const val PREFS = "location_share"
        private const val KEY_SERVER = "server_url"
        private const val KEY_USER = "username"
        private const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etUser = findViewById<EditText>(R.id.etUsername)
        val etPass = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        etServer.setText(prefs.getString(KEY_SERVER, DEFAULT_SERVER))
        etUser.setText(prefs.getString(KEY_USER, ""))

        fun applyServer() {
            var url = etServer.text.toString().trim().trimEnd('/')
            if (url.isEmpty()) {
                url = DEFAULT_SERVER
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            ApiClient.baseUrl = url
            prefs.edit()
                .putString(KEY_SERVER, url)
                .putString(KEY_USER, etUser.text.toString().trim())
                .apply()
            etServer.setText(url)
        }

        btnLogin.setOnClickListener {
            applyServer()
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "请输入账号密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiClient.login(u, p) { result ->
                runOnUiThread {
                    result.onSuccess {
                        startActivity(Intent(this, DeviceListActivity::class.java))
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnRegister.setOnClickListener {
            applyServer()
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString()
            if (u.length < 3 || p.length < 6) {
                Toast.makeText(this, "用户名≥3 密码≥6", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiClient.register(u, p) { result ->
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this, "注册成功", Toast.LENGTH_SHORT).show()
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
