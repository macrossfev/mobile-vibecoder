package com.vibecoder.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vibecoder.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ServerListFragment())
                .commit()
        }
    }
}