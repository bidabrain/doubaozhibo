package com.doubao.live

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.doubao.live.databinding.FragmentScheduleBinding
import com.doubao.live.databinding.ItemDateHeaderBinding
import com.doubao.live.databinding.ItemScheduleBinding
import com.google.android.material.R as MR
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleFragment : Fragment(), Reselectable {

    private var _b: FragmentScheduleBinding? = null
    private val b get() = _b!!

    private val adapter = ScheduleAdapter { m ->
        val id = m.playId
        when {
            id == null -> Toast.makeText(requireContext(), "这场比赛暂无直播信号", Toast.LENGTH_SHORT).show()
            m.status == 2 -> Toast.makeText(requireContext(), "比赛已结束", Toast.LENGTH_SHORT).show()
            else -> PlayerActivity.start(requireContext(), id)
        }
    }
    private val matches = mutableListOf<Match>()
    private var category = "all"
    private var cursor: String? = null
    private var hasMore = false
    private var loading: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentScheduleBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.categories.chips.setupCategories(category) { category = it; reload() }

        val grid = GridLayoutManager(requireContext(), spanCount())
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.getItemViewType(position) == ScheduleAdapter.TYPE_HEADER) grid.spanCount else 1
        }
        b.list.layoutManager = grid
        b.list.adapter = adapter
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && grid.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadMore()
            }
        })
        b.refresh.setOnRefreshListener { reload() }
        reload()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (b.list.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loading?.cancel()
        _b = null
    }

    override fun onReselect() {
        b.list.scrollToPosition(0)
        reload()
    }

    /** 手机竖屏 1 列，平板 / 横屏 / 电视 2 列 */
    private fun spanCount() = if (resources.configuration.screenWidthDp >= 720) 2 else 1

    private fun reload() {
        loading?.cancel()
        matches.clear()
        cursor = null
        hasMore = false
        adapter.submit(emptyList())
        fetch()
    }

    private fun loadMore() {
        if (loading?.isActive == true || !hasMore) return
        fetch()
    }

    private fun fetch() {
        b.empty.visibility = View.GONE
        if (cursor == null) b.refresh.isRefreshing = true
        loading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = Api.schedules(Times.today(), category, cursor)
                val known = matches.mapTo(HashSet()) { it.id }
                matches += res.items.filter { it.id !in known }
                cursor = res.nextCursor
                hasMore = res.hasMore
                adapter.submit(buildRows(matches))
                if (matches.isEmpty()) {
                    b.empty.text = "近期暂无赛程"
                    b.empty.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (matches.isEmpty()) {
                    b.empty.text = "加载失败：${e.message}\n下拉重试"
                    b.empty.visibility = View.VISIBLE
                }
            } finally {
                _b?.refresh?.isRefreshing = false
            }
        }
    }

    /** 按本地日期分组，插入日期标题 */
    private fun buildRows(list: List<Match>): List<ScheduleAdapter.Row> {
        val rows = mutableListOf<ScheduleAdapter.Row>()
        var lastDay = ""
        list.sortedBy { Times.parse(it.matchTime)?.time ?: Long.MAX_VALUE }.forEach { m ->
            val day = Times.format(m.matchTime, "yyyy-MM-dd")
            if (day != lastDay) {
                rows += ScheduleAdapter.Row.Header(dayLabel(m.matchTime))
                lastDay = day
            }
            rows += ScheduleAdapter.Row.Item(m)
        }
        return rows
    }

    private fun dayLabel(iso: String): String {
        val date = Times.parse(iso) ?: return iso.take(10)
        val cal = Calendar.getInstance().apply { time = date }
        val today = Calendar.getInstance()
        val diff = ((startOfDay(cal) - startOfDay(today)) / 86_400_000L).toInt()
        val prefix = when (diff) {
            -1 -> "昨天 · "
            0 -> "今天 · "
            1 -> "明天 · "
            2 -> "后天 · "
            else -> ""
        }
        return prefix + SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(date)
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

class ScheduleAdapter(private val onClick: (Match) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val match: Match) : Row()
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(ItemDateHeaderBinding.inflate(inf, parent, false))
        else ItemVH(ItemScheduleBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).b.date.text = row.label
            is Row.Item -> bind((holder as ItemVH).b, row.match)
        }
    }

    private fun bind(b: ItemScheduleBinding, m: Match) {
        val ctx = b.root
        val primary = MaterialColors.getColor(ctx, MR.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(ctx, MR.attr.colorOnPrimary)
        val variant = MaterialColors.getColor(ctx, MR.attr.colorSurfaceVariant)
        val onVariant = MaterialColors.getColor(ctx, MR.attr.colorOnSurfaceVariant)
        val live = ctx.context.getColor(R.color.live)

        b.time.text = Times.format(m.matchTime, "HH:mm")
        b.league.text = m.league
        b.home.text = m.home
        b.away.text = m.away
        b.homeLogo.load(m.homeLogo) { crossfade(true) }
        b.awayLogo.load(m.awayLogo) { crossfade(true) }

        when (m.status) {
            1 -> {
                b.score.text = "${m.homeScore} - ${m.awayScore}"
                b.status.text = "● 进行中"
                b.status.setTextColor(live)
            }
            2 -> {
                b.score.text = "${m.homeScore} - ${m.awayScore}"
                b.status.text = "已完赛"
                b.status.setTextColor(onVariant)
            }
            else -> {
                b.score.text = "VS"
                b.status.text = "未开始"
                b.status.setTextColor(onVariant)
            }
        }

        val watchable = m.playId != null && m.status == 1
        b.action.text = when {
            watchable -> "观看"
            m.status == 2 -> "完赛"
            m.playId == null -> "无信号"
            else -> "待开播"
        }
        b.action.backgroundTintList = ColorStateList.valueOf(if (watchable) primary else variant)
        b.action.setTextColor(if (watchable) onPrimary else onVariant)
        b.root.setOnClickListener { onClick(m) }
        b.root.focusZoom(1.02f)
    }

    class HeaderVH(val b: ItemDateHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ItemVH(val b: ItemScheduleBinding) : RecyclerView.ViewHolder(b.root)
}
