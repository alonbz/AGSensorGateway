package com.agsense.ksensorgateway

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows discovered KSensor readings. Keeps a master list of everything
 * seen (so nothing is lost when the filter changes) and a currently
 * visible/filtered subset that the RecyclerView actually renders.
 *
 * [onItemClick] lets the host Activity react to a tap — used to copy the
 * tapped row's MAC into the filter field, so "pick from the list" and
 * "type a MAC" are two paths to the same result.
 */
class SensorAdapter(private val onItemClick: (SensorReading) -> Unit = {}) :
    RecyclerView.Adapter<SensorAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val allItems = mutableListOf<SensorReading>()
    private val visibleItems = mutableListOf<SensorReading>()

    /** Normalized (uppercase, no separators) filter text; empty = show everything. */
    private var filterQuery: String = ""

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
        val item = visibleItems[position]
        holder.title.text = item.name ?: item.mac
        holder.subtitle.text = "${item.mac}  •  RSSI ${item.rssi} dBm  •  ${timeFormat.format(Date(item.lastUpdateMillis))}"

        val parts = mutableListOf<String>()
        item.temperatureC?.let { parts.add(String.format(Locale.getDefault(), "🌡 %.1f°C", it)) }
        item.humidityPct?.let { parts.add(String.format(Locale.getDefault(), "💧 %.1f%%", it)) }
        item.batteryMv?.let { parts.add("🔋 ${it} mV") }
        item.batteryPercent?.let { parts.add("🔋 ${it}%") }
        // Only shown once all three axes are present — a beacon lying flat
        // reads roughly X≈0, Y≈0, Z≈1000mg (section 2 of the K6 supplement).
        if (item.accXmg != null && item.accYmg != null && item.accZmg != null) {
            parts.add("📐 X:${item.accXmg} Y:${item.accYmg} Z:${item.accZmg} mg")
        }
        holder.reading.text = if (parts.isEmpty()) "ממתין לנתונים..." else parts.joinToString("   ")

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = visibleItems.size

    /** Insert or update a reading in the master list, then refresh what's visible under the current filter. */
    fun upsert(newReading: SensorReading) {
        val existingIndex = allItems.indexOfFirst { it.mac == newReading.mac }
        if (existingIndex >= 0) {
            allItems[existingIndex] = newReading
        } else {
            allItems.add(newReading)
        }
        applyFilter()
    }

    /** Filter by MAC (colon/space-insensitive) or by name, case-insensitive substring match. Empty = show all. */
    fun setFilter(query: String) {
        filterQuery = normalize(query)
        applyFilter()
    }

    private fun normalize(s: String): String = s.uppercase(Locale.ROOT).replace(Regex("[:\\s-]"), "")

    private fun applyFilter() {
        visibleItems.clear()
        if (filterQuery.isEmpty()) {
            visibleItems.addAll(allItems)
        } else {
            visibleItems.addAll(
                allItems.filter { item ->
                    normalize(item.mac).contains(filterQuery) ||
                        (item.name?.let { normalize(it).contains(filterQuery) } == true)
                }
            )
        }
        // Sizes here are small (a handful to a few dozen sensors), so a
        // full refresh is simpler and safer than diffing — no risk of
        // stale positions after a filter change.
        notifyDataSetChanged()
    }
}
