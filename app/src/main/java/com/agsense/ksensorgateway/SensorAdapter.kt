package com.agsense.ksensorgateway

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorAdapter(private val items: MutableList<SensorReading>) :
    RecyclerView.Adapter<SensorAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textTitle)
        val subtitle: TextView = view.findViewById(R.id.textSubtitle)
        val reading: TextView = view.findViewById(R.id.textReading)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sensor, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name ?: item.mac
        holder.subtitle.text = "${item.mac}  •  RSSI ${item.rssi} dBm  •  ${timeFormat.format(Date(item.lastUpdateMillis))}"

        val parts = mutableListOf<String>()
        item.temperatureC?.let { parts.add(String.format(Locale.getDefault(), "🌡 %.1f°C", it)) }
        item.humidityPct?.let { parts.add(String.format(Locale.getDefault(), "💧 %.1f%%", it)) }
        item.batteryMv?.let { parts.add("🔋 ${it} mV") }
        holder.reading.text = if (parts.isEmpty()) "ממתין לנתונים..." else parts.joinToString("   ")
    }

    override fun getItemCount(): Int = items.size

    /** Insert or update a reading, keeping the list sorted by MAC, and refresh the UI. */
    fun upsert(newReading: SensorReading) {
        val existingIndex = items.indexOfFirst { it.mac == newReading.mac }
        if (existingIndex >= 0) {
            items[existingIndex] = newReading
            notifyItemChanged(existingIndex)
        } else {
            items.add(newReading)
            notifyItemInserted(items.size - 1)
        }
    }
}
