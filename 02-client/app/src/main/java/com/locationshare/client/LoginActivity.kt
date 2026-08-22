package com.locationshare.client

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.locationshare.client.api.ApiClient

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etUser = findViewById<EditText>(R.id.etUsername)
        val etPass = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        btnLogin.setOnClickListener {
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
