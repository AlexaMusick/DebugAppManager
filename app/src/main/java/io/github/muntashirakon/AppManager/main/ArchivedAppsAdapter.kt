// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.main

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.muntashirakon.AppManager.R
import io.github.muntashirakon.AppManager.db.entity.ArchivedApp

class ArchivedAppsAdapter(
    private var archivedApps: List<ArchivedApp>,
    private val listener: (ArchivedApp) -> Unit
) : RecyclerView.Adapter<ArchivedAppsAdapter.ViewHolder>() {

    fun interface OnRestoreClickListener {
        fun onRestoreClicked(archivedApp: ArchivedApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_archived_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val archivedApp = archivedApps[position]
        holder.appName.text = archivedApp.appName ?: archivedApp.packageName
        holder.packageName.text = archivedApp.packageName
        holder.archiveDate.text = if (archivedApp.archiveTimestamp > 0) {
            DateUtils.getRelativeTimeSpanString(
                archivedApp.archiveTimestamp,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS
            )
        } else {
            ""
        }
        if (archivedApp.tags != null) {
            holder.tags.visibility = View.VISIBLE
            holder.tags.text = archivedApp.tags
        } else {
            holder.tags.visibility = View.GONE
        }
        holder.restoreButton.setOnClickListener { listener(archivedApp) }
    }

    override fun getItemCount(): Int = archivedApps.size

    fun updateData(newArchivedApps: List<ArchivedApp>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = archivedApps.size
            override fun getNewListSize() = newArchivedApps.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                archivedApps[oldPos].packageName == newArchivedApps[newPos].packageName
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                archivedApps[oldPos].packageName == newArchivedApps[newPos].packageName &&
                        archivedApps[oldPos].archiveTimestamp == newArchivedApps[newPos].archiveTimestamp &&
                        archivedApps[oldPos].appName == newArchivedApps[newPos].appName &&
                        archivedApps[oldPos].tags == newArchivedApps[newPos].tags
        })
        archivedApps = newArchivedApps
        diff.dispatchUpdatesTo(this)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appName: TextView = view.findViewById(R.id.app_name)
        val packageName: TextView = view.findViewById(R.id.package_name)
        val archiveDate: TextView = view.findViewById(R.id.archive_date)
        val tags: TextView = view.findViewById(R.id.tags)
        val restoreButton: Button = view.findViewById(R.id.restore_button)
    }
}
