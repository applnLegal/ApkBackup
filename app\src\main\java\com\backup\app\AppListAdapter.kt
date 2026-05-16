package com.backup.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val apps: List<MainActivity.AppInfo>,
    private val onItemChecked: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvName)
        val packageName: TextView = view.findViewById(R.id.tvPackageName)
        val size: TextView = view.findViewById(R.id.tvSize)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]

        holder.name.text = app.name
        holder.packageName.text = app.packageName
        holder.size.text = formatSize(app.size)
        holder.icon.setImageDrawable(app.icon)
        holder.checkbox.isChecked = app.isSelected

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            app.isSelected = isChecked
            onItemChecked(position, isChecked)
        }

        holder.itemView.setOnClickListener {
            holder.checkbox.isChecked = !holder.checkbox.isChecked
        }
    }

    override fun getItemCount() = apps.size

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
}
