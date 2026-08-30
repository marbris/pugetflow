package com.pugetflow

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

/** Lists the rivers from RiverCatalog; tapping one opens its flow/temp graphs. */
class RiversActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rivers)
        findViewById<android.view.View>(R.id.root).applySystemBarInsets()

        val rivers = RiverCatalog.RIVERS
        val list = findViewById<ListView>(R.id.list)
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rivers.map { it.name }
        )
        list.setOnItemClickListener { _, _, position, _ ->
            val river = rivers[position]
            startActivity(Intent(this, RiverDetailActivity::class.java).apply {
                putExtra(RiverDetailActivity.EXTRA_NAME, river.name)
                putExtra(RiverDetailActivity.EXTRA_SEED, river.seedSiteId)
            })
        }
    }
}
