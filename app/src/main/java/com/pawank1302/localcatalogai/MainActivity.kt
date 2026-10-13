package com.pawank1302.localcatalogai

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            preview.setImageURI(uri)
            status.text = "Photo loaded locally. Ready for the on-device processing pipeline."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.selectPhoto).setOnClickListener { pickImage.launch("image/*") }
    }
}
