package com.doubao.live

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.doubao.live.databinding.ActivityMainBinding
import com.doubao.live.databinding.ItemRoomBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = RoomAdapter { room ->
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PLAY_ID, room.playId))
    }

    private val categories = listOf("all" to "全部", "important" to "热门", "football" to "足球", "basketball" to "篮球", "other" to "其他")
    private var category = "all"
    private var query = ""
    private var page = 0
    private var total = 0
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        categories.forEach { (_, label) -> b.tabs.addTab(b.tabs.newTab().setText(label)) }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                category = categories[tab.position].first
                reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = reload()
        })

        val grid = GridLayoutManager(this, spanCount())
        b.list.layoutManager = grid
        b.list.adapter = adapter
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && grid.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })

        b.refresh.setOnRefreshListener { reload() }
        b.search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                query = v.text.toString().trim()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                reload()
                true
            } else false
        }

        reload()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (b.list.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    private fun spanCount(): Int {
        val dp = resources.configuration.screenWidthDp
        return (dp / 180).coerceIn(2, 6)
    }

    private fun reload() {
        loading?.cancel()
        page = 0
        total = 0
        adapter.submit(emptyList(), append = false)
        fetch(1)
    }

    private fun loadMore() {
        if (loading?.isActive == true) return
        if (page == 0 || adapter.itemCount >= total) return
        fetch(page + 1)
    }

    private fun fetch(p: Int) {
        b.empty.visibility = View.GONE
        if (p == 1) b.refresh.isRefreshing = true
        loading = lifecycleScope.launch {
            try {
                val res = Api.rooms(category, p, query)
                page = res.page
                total = res.total
                adapter.submit(res.items, append = p > 1)
                if (adapter.itemCount == 0) {
                    b.empty.text = if (query.isNotEmpty()) "没有找到相关直播" else "当前暂无直播\n下拉刷新"
                    b.empty.visibility = View.VISIBLE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (adapter.itemCount == 0) {
                    b.empty.text = "加载失败：${e.message}\n下拉重试"
                    b.empty.visibility = View.VISIBLE
                }
            } finally {
                b.refresh.isRefreshing = false
            }
        }
    }
}

class RoomAdapter(private val onClick: (Room) -> Unit) : RecyclerView.Adapter<RoomAdapter.VH>() {
    private val items = mutableListOf<Room>()

    fun submit(list: List<Room>, append: Boolean) {
        if (!append) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        } else {
            val known = items.mapTo(HashSet()) { it.id }
            val fresh = list.filter { it.id !in known }
            val start = items.size
            items.addAll(fresh)
            notifyItemRangeInserted(start, fresh.size)
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        with(holder.b) {
            title.text = r.title
            anchor.text = r.anchor
            badge.text = if (r.status == 1) "直播中" else r.league.ifEmpty { "直播" }
            popularity.text = "🔥 ${formatPop(r.popularity)}"
            cover.load(r.cover) { crossfade(true) }
            root.setOnClickListener { onClick(r) }
        }
    }

    private fun formatPop(n: Int) = if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()

    class VH(val b: ItemRoomBinding) : RecyclerView.ViewHolder(b.root)
}
