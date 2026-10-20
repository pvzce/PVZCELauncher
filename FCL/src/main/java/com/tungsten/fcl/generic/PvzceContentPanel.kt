package com.tungsten.fcl.generic

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.ItemResourcePackBinding
import com.tungsten.fcl.databinding.PageManageResourcePackBinding
import com.tungsten.fcllibrary.component.dialog.EditDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLButton
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Reuses FCL's management page, row, dialogs and selection behavior with PVZCE storage. */
class PvzceContentPanel(
    private val activity: GenericLauncherActivity,
    private val store: () -> PvzceContentStore,
    private val setHostBusy: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val importContent: (PvzceContentStore.Kind, Boolean) -> Unit,
    private val exportContent: (PvzceContentStore.Kind, List<String>) -> Unit
) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val summary = FCLTextView(activity).apply { setUseThemeColor(true); textSize = 13f }
    private val tabs = TabLayout(activity).apply { id = R.id.pvzce_content_tabs; tabMode = TabLayout.MODE_SCROLLABLE }
    private val binding = PageManageResourcePackBinding.inflate(activity.layoutInflater, view, false)
    private val selected = linkedSetOf<String>()
    private var all = emptyList<PvzceContentStore.Entry>()
    private var visible = emptyList<PvzceContentStore.Entry>()
    private var busy = false
    private var restoring = false
    val query get() = binding.searchFilter.text.toString()
    private val extraButtons = mutableListOf<FCLButton>()
    var kind = PvzceContentStore.Kind.MODS
        private set
    private val names = listOf("模组", "资源包", "数据包", "存档")
    private val adapter = object : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = visible.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
            Row(ItemResourcePackBinding.inflate(LayoutInflater.from(activity), parent, false))
        override fun onBindViewHolder(holder: Row, position: Int) {
            val entry = visible[position]
            holder.bind(entry)
        }
    }

    init {
        view.addView(summary)
        names.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        view.addView(tabs)
        view.addView(binding.root, LinearLayout.LayoutParams(-1, 0, 1f))
        binding.list.layoutManager = LinearLayoutManager(activity)
        binding.list.adapter = adapter
        binding.add.text = "导入 JAR / 目录"
        binding.add.setOnClickListener { if (!busy) importContent(kind, false) }
        binding.download.text = "导出本页备份 ZIP"
        binding.download.setOnClickListener { backup(all.map { it.filename }) }
        binding.refresh.setOnClickListener { reload() }
        binding.delete.text = "移至回收站"
        binding.delete.setOnClickListener {
            if (busy || selected.isEmpty()) return@setOnClickListener
            val snapshot = selected.toList()
            val target = store()
            confirm("移除 ${snapshot.size} 项？", "内容会移到当前实例的回收站，可恢复；其他实例不受影响。") {
                perform(target) { snapshot.forEach { trash(kind, it) }; "已移入回收站" }
            }
        }
        binding.selectAll.setOnClickListener { selected.addAll(visible.map { it.filename }); updateSelection() }
        binding.selectInvert.setOnClickListener {
            val inverse = visible.map { it.filename }.filter { it !in selected }
            selected.clear(); selected.addAll(inverse); updateSelection()
        }
        binding.cancel.setOnClickListener { selected.clear(); updateSelection() }
        button(binding.normalLayout, "从备份 ZIP 导入") { importContent(kind, true) }
        button(binding.normalLayout, "恢复已移除内容") { recover() }
        button(binding.selectedLayout, "导出所选备份 ZIP") { backup(selected.toList()) }
        binding.searchFilter.doAfterTextChanged { filter() }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                kind = PvzceContentStore.Kind.values()[tab.position]
                selected.clear()
                binding.searchFilter.setText("")
                if (!restoring) reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun button(parent: ViewGroup, label: String, action: () -> Unit) {
        val button = activity.layoutInflater.inflate(R.layout.pvzce_action_button, parent, false) as FCLButton
        button.text = label
        button.setOnClickListener { if (!busy) action() }
        extraButtons.add(button)
        parent.addView(button)
    }

    fun setBusy(value: Boolean) {
        busy = value
        listOf(binding.add, binding.download, binding.refresh, binding.delete, binding.selectAll,
            binding.selectInvert, binding.cancel, binding.searchFilter).forEach { it.isEnabled = !value }
        extraButtons.forEach { it.isEnabled = !value }
        for (i in 0 until tabs.tabCount) tabs.getTabAt(i)?.view?.isEnabled = !value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    fun refreshTheme() {
        adapter.notifyDataSetChanged()
    }

    fun restoreState(index: Int, query: String?) {
        restoring = true
        kind = PvzceContentStore.Kind.values()[index.coerceIn(0, 3)]
        tabs.selectTab(tabs.getTabAt(kind.ordinal))
        binding.searchFilter.setText(query ?: "")
        restoring = false
    }

    fun reload(clearSelection: Boolean = false) {
        if (busy || activity.isDestroyed || activity.isFinishing) return
        if (clearSelection) selected.clear()
        all = emptyList(); visible = emptyList(); filter()
        val target = runCatching { store() }.getOrElse { onError(it); return }
        val capturedKind = kind
        setHostBusy(true)
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { target.list(capturedKind) } }
                result.onSuccess {
                    all = it
                    selected.retainAll(it.map { entry -> entry.filename }.toSet())
                    filter()
                }.onFailure { onError(it) }
            } finally { setHostBusy(false) }
        }
    }

    private fun filter() {
        val query = binding.searchFilter.text.toString().trim().lowercase(Locale.ROOT)
        visible = all.filter { query.isEmpty() || (it.title + it.filename + it.description).lowercase(Locale.ROOT).contains(query) }
        selected.retainAll(visible.map { it.filename }.toSet())
        summary.text = "${names[kind.ordinal]} · ${visible.size} 项 · ${selected.size} 项已选\n" +
            if (all.isEmpty()) "当前实例暂无内容，可导入目录或备份。" else "点击多选，长按重命名；勾选启用。重启游戏后生效。"
        updateSelection()
    }

    private fun updateSelection() {
        binding.normalLayout.visibility = if (selected.isEmpty()) View.VISIBLE else View.GONE
        binding.selectedLayout.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun backup(items: List<String>) {
        if (busy) return
        if (items.isEmpty()) onStatus("没有可备份的内容") else exportContent(kind, items)
    }

    private fun recover() {
        val target = store()
        setHostBusy(true)
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { target.trashIds().map { it to target.trashLabel(it) } } }
                result.onSuccess { entries ->
                    if (entries.isEmpty()) { onStatus("当前实例回收站为空"); return@onSuccess }
                    val dialog = com.tungsten.fcllibrary.component.dialog.FCLDialog(activity)
                    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    val title = FCLTextView(activity).apply { text = "恢复内容（同名内容不会覆盖）"; setUseThemeColor(true) }
                    body.addView(title)
                    entries.forEach { (id, label) ->
                        val button = activity.layoutInflater.inflate(R.layout.pvzce_action_button, body, false) as FCLButton
                        button.text = label
                        button.setOnClickListener { dialog.dismiss(); perform(target) { restore(id); "内容已恢复" } }
                        body.addView(button)
                    }
                    val scroll = android.widget.ScrollView(activity).apply { addView(body) }
                    dialog.setContentView(scroll)
                    dialog.show()
                    dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .7f).toInt(),
                        (activity.resources.displayMetrics.heightPixels * .8f).toInt())
                }.onFailure { onError(it) }
            } finally { setHostBusy(false) }
        }
    }

    private fun perform(target: PvzceContentStore = store(), action: PvzceContentStore.() -> String) {
        if (busy) return
        setHostBusy(true)
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { target.action() } }
                result.onSuccess { onStatus(it) }.onFailure { onError(it) }
            } finally {
                setHostBusy(false)
                reload()
            }
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        val dialog = FCLAlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("确定") { action() }.setNegativeButton("取消", null).create()
        dialog.setOnDismissListener { adapter.notifyDataSetChanged() }
        dialog.show()
    }

    private inner class Row(val binding: ItemResourcePackBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: PvzceContentStore.Entry) {
            binding.name.text = entry.title
            binding.description.text = entry.filename + " · " + entry.description
            binding.tag.visibility = View.GONE
            binding.icon.setImageResource(if (kind == PvzceContentStore.Kind.SAVES) R.drawable.ic_baseline_folder_24 else R.drawable.ic_baseline_texture_24)
            binding.icon.imageTintList = ColorStateList.valueOf(ThemeEngine.getTheme().getColor())
            binding.parent.backgroundTintList = ColorStateList.valueOf(
                if (entry.filename in selected) ThemeEngine.getTheme().getColor() else ThemeEngine.getTheme().ltColor)
            binding.parent.isEnabled = !busy
            binding.parent.setOnClickListener {
                if (!selected.remove(entry.filename)) selected.add(entry.filename)
                filter()
            }
            binding.parent.setOnLongClickListener {
                if (!busy) EditDialog(activity, entry.filename) { name -> perform { rename(kind, entry.filename, name); "已重命名" } }
                    .apply { setTitle("重命名") }.show()
                true
            }
            binding.check.visibility = if (kind == PvzceContentStore.Kind.SAVES) View.INVISIBLE else View.VISIBLE
            binding.check.isEnabled = !busy
            binding.check.setOnCheckedChangeListener(null)
            binding.check.isChecked = entry.enabled
            binding.check.contentDescription = "启用 ${entry.title}"
            binding.check.setOnCheckedChangeListener { _, enabled ->
                val target = store()
                runCatching { target.hasSharedPackName(kind, entry.filename) }.onSuccess { shared ->
                    if (shared) {
                        binding.check.setOnCheckedChangeListener(null); binding.check.isChecked = entry.enabled
                        confirm("同名包共享开关", "PVZCE 按目录名保存开关，另一类同名包也会一起改变。是否继续？") {
                            perform(target) { setEnabled(kind, entry.filename, enabled); "已更新包开关" }
                        }
                    } else perform(target) { setEnabled(kind, entry.filename, enabled); "已更新启用状态" }
                }.onFailure { onError(it); reload() }
            }
            binding.info.isEnabled = !busy
            binding.info.setOnClickListener {
                val dialog = FCLAlertDialog.Builder(activity).setTitle(entry.title)
                    .setMessage("${entry.filename}\n${entry.description}\n修改时间：${java.util.Date(entry.modified)}")
                    .setPositiveButton("确定", null)
                if (entry.workshopProject > 0) dialog.setNeutralButton("查看工坊版本 / 更新") {
                    activity.startActivity(android.content.Intent(activity, PvzceWorkshopActivity::class.java)
                        .putExtra("api", entry.workshopApi).putExtra("project", entry.workshopProject)
                        .putExtra("instance", activity.workshopInstanceId()))
                }
                dialog.create().show()
            }
        }
    }
}
