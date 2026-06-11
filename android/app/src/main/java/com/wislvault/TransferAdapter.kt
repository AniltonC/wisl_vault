package com.wislvault

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wislvault.databinding.ItemTransferBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransferAdapter(
    private val onItemClick: (TransferItem) -> Unit
) : ListAdapter<TransferItem, TransferAdapter.ViewHolder>(DIFF) {

    var isSelectionMode = false
    var selectedIds: Set<String> = emptySet()

    inner class ViewHolder(private val b: ItemTransferBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: TransferItem) {
            b.tvTransferName.text = item.fileName

            val fileIconRes = when {
                item.mimeType.startsWith("image/") -> R.drawable.ic_file_image
                item.mimeType.startsWith("video/") -> R.drawable.ic_file_video
                item.mimeType.startsWith("audio/") -> R.drawable.ic_file_audio
                else -> R.drawable.ic_file_generic
            }
            b.ivFileIcon.setImageResource(fileIconRes)

            b.ivArrowUpload.isVisible = item.direction == TransferItem.Direction.UPLOAD
            b.ivArrowDownload.isVisible = item.direction == TransferItem.Direction.DOWNLOAD

            when (item.status) {
                TransferItem.Status.ACTIVE -> bindActive(item)
                TransferItem.Status.COMPLETED -> bindCompleted(item)
                TransferItem.Status.CANCELLED -> bindReason(b.root.context.getString(R.string.transfer_status_cancelled))
                TransferItem.Status.FAILED -> bindReason(b.root.context.getString(R.string.transfer_status_failed))
            }

            b.cbSelected.isVisible = isSelectionMode
            b.cbSelected.isChecked = selectedIds.contains(item.id)

            b.root.setOnClickListener { onItemClick(item) }
        }

        fun bindProgress(item: TransferItem) {
            val pct = if (item.fileSize > 0)
                ((item.bytesTransferred.toDouble() / item.fileSize) * 100).toInt()
            else 0
            b.transferProgressBar.progress = pct
            b.tvTransferPercent.text = "$pct%"
            val transferred = TransferManager.formatSize(item.bytesTransferred)
            val total = if (item.fileSize > 0) TransferManager.formatSize(item.fileSize) else "?"
            b.tvTransferBytes.text = "$transferred de $total"
            b.tvTransferSpeed.text = if (item.speed > 0)
                "${TransferManager.formatSize(item.speed.toLong())}/s"
            else ""
        }

        private fun bindActive(item: TransferItem) {
            val pct = if (item.fileSize > 0)
                ((item.bytesTransferred.toDouble() / item.fileSize) * 100).toInt()
            else 0

            b.transferProgressBar.progress = pct
            b.tvTransferPercent.text = "$pct%"

            val transferred = TransferManager.formatSize(item.bytesTransferred)
            val total = if (item.fileSize > 0) TransferManager.formatSize(item.fileSize) else "?"
            b.tvTransferBytes.text = "$transferred de $total"

            b.tvTransferSpeed.text = if (item.speed > 0)
                "${TransferManager.formatSize(item.speed.toLong())}/s"
            else ""

            b.transferProgressBar.isVisible = true
            b.tvTransferPercent.isVisible = true
            b.tvTransferBytes.isVisible = true
            b.tvTransferSpeed.isVisible = true
            b.tvTransferReason.isVisible = false
            b.tvTransferCompleted.isVisible = false
        }

        private fun bindCompleted(item: TransferItem) {
            val size = TransferManager.formatSize(item.fileSize)
            val date = item.completedAt?.let {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(it))
            } ?: ""
            b.tvTransferCompleted.text = if (date.isNotEmpty()) "$size · $date" else size

            b.transferProgressBar.isVisible = false
            b.tvTransferPercent.isVisible = false
            b.tvTransferBytes.isVisible = false
            b.tvTransferSpeed.isVisible = false
            b.tvTransferReason.isVisible = false
            b.tvTransferCompleted.isVisible = true
        }

        private fun bindReason(reason: String) {
            b.tvTransferReason.text = reason

            b.transferProgressBar.isVisible = false
            b.tvTransferPercent.isVisible = false
            b.tvTransferBytes.isVisible = false
            b.tvTransferSpeed.isVisible = false
            b.tvTransferReason.isVisible = true
            b.tvTransferCompleted.isVisible = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "progress") {
            holder.bindProgress(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransferItem>() {
            override fun areItemsTheSame(a: TransferItem, b: TransferItem) = a.id == b.id
            override fun areContentsTheSame(a: TransferItem, b: TransferItem) = a == b

            override fun getChangePayload(oldItem: TransferItem, newItem: TransferItem): Any? {
                return if (oldItem.status == newItem.status &&
                           oldItem.status == TransferItem.Status.ACTIVE &&
                           (oldItem.bytesTransferred != newItem.bytesTransferred ||
                            oldItem.speed != newItem.speed))
                    "progress"
                else null
            }
        }
    }
}
