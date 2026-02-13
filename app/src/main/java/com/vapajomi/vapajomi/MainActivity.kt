package com.vapajomi.vapajomi

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        

        val button = Button(this)
        button.text = "PROBAR FIREBASE"

        val layout = findViewById<android.widget.LinearLayout>(R.id.main)
        layout.addView(button)

    }
}