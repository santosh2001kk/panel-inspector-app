package com.example.testerapigoogle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_logged_in", false)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login)

        val etUsername = findViewById<TextInputEditText>(R.id.etUsername)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val cbRemember = findViewById<CheckBox>(R.id.cbRemember)
        val btnLogin   = findViewById<MaterialButton>(R.id.btnLogin)
        val tvError    = findViewById<TextView>(R.id.tvError)

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            when {
                username.isEmpty() -> {
                    tvError.text = "Please enter your username"
                    tvError.visibility = View.VISIBLE
                }
                password.isEmpty() -> {
                    tvError.text = "Please enter your password"
                    tvError.visibility = View.VISIBLE
                }
                else -> {
                    tvError.visibility = View.GONE
                    btnLogin.isEnabled = false
                    btnLogin.text = "Signing in..."
                    attemptLogin(username, password, cbRemember.isChecked, btnLogin, tvError)
                }
            }
        }
    }

    private fun attemptLogin(
        username: String,
        password: String,
        remember: Boolean,
        btnLogin: MaterialButton,
        tvError: TextView
    ) {
        val serverUrl = GoogleStudioDetector.BASE_URL.replace("/api/analyze", "/api/login")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder().url(serverUrl).post(body).build()
                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                val success = json.optBoolean("success", false)
                val message = json.optString("message", "Login failed")

                withContext(Dispatchers.Main) {
                    btnLogin.isEnabled = true
                    btnLogin.text = "Sign In"
                    if (success) {
                        getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_logged_in", remember)
                            .putString("username", username)
                            .apply()
                        goToMain()
                    } else {
                        tvError.text = message
                        tvError.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnLogin.isEnabled = true
                    btnLogin.text = "Sign In"
                    tvError.text = "Cannot reach server. Make sure the server is running."
                    tvError.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
