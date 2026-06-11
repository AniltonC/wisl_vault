package com.wislvault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.wislvault.databinding.ActivityTransfersBinding
import kotlinx.coroutines.launch

class TransfersActivity : Fragment() {

    private var _binding: ActivityTransfersBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TransferAdapter

    private var currentTab = Tab.ACTIVE
    private var isSelectionMode = false
    private val selectedIds = mutableSetOf<String>()
    private var currentTabItems = listOf<TransferItem>()

    private enum class Tab { ACTIVE, COMPLETED, FAILED }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityTransfersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TransferAdapter { item ->
            if (isSelectionMode) toggleSelection(item.id)
        }

        binding.rvTransfers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTransfers.adapter = adapter
        binding.rvTransfers.itemAnimator = null

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_active))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_completed))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.tab_failed))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = when (tab.position) {
                    0 -> Tab.ACTIVE
                    1 -> Tab.COMPLETED
                    else -> Tab.FAILED
                }
                exitSelectionMode()
                refreshList(TransferManager.transfers.value)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (isSelectionMode) {
                    if (selectedIds.isNotEmpty()) {
                        menuInflater.inflate(R.menu.menu_transfers_selection, menu)
                    }
                } else if (currentTabItems.isNotEmpty()) {
                    menuInflater.inflate(R.menu.menu_transfers_normal, menu)
                    val allItem = menu.findItem(R.id.action_manage_all)
                    allItem?.title = getString(
                        if (currentTab == Tab.ACTIVE) R.string.action_cancel_all
                        else R.string.action_clear_all
                    )
                }
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_select -> { enterSelectionMode(); true }
                    R.id.action_manage_all -> { manageAll(); true }
                    R.id.action_delete_selected -> { deleteSelected(); true }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSelectionMode) {
                        exitSelectionMode()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            TransferManager.transfers.collect { transfers ->
                refreshList(transfers)
            }
        }
    }

    private fun refreshList(transfers: List<TransferItem>) {
        val filtered = transfers.filter { item ->
            when (currentTab) {
                Tab.ACTIVE -> item.status == TransferItem.Status.ACTIVE
                Tab.COMPLETED -> item.status == TransferItem.Status.COMPLETED
                Tab.FAILED -> item.status == TransferItem.Status.FAILED
                              || item.status == TransferItem.Status.CANCELLED
            }
        }

        currentTabItems = filtered

        // Remove from selectedIds any item that's no longer in this tab
        selectedIds.retainAll(filtered.map { it.id }.toSet())

        adapter.isSelectionMode = isSelectionMode
        adapter.selectedIds = selectedIds.toSet()
        adapter.submitList(filtered)

        val isEmpty = filtered.isEmpty()
        binding.tvNoTransfers.isVisible = isEmpty
        binding.rvTransfers.isVisible = !isEmpty
        binding.tvNoTransfers.text = getString(
            when (currentTab) {
                Tab.ACTIVE -> R.string.no_transfers_active
                Tab.COMPLETED -> R.string.no_transfers_completed
                Tab.FAILED -> R.string.no_transfers_failed
            }
        )

        requireActivity().invalidateOptionsMenu()
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedIds.clear()
        syncAdapterSelection()
        requireActivity().invalidateOptionsMenu()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        syncAdapterSelection()
        requireActivity().invalidateOptionsMenu()
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        syncAdapterSelection()
        requireActivity().invalidateOptionsMenu()
    }

    private fun syncAdapterSelection() {
        adapter.isSelectionMode = isSelectionMode
        adapter.selectedIds = selectedIds.toSet()
        adapter.notifyDataSetChanged()
    }

    private fun manageAll() {
        if (currentTab == Tab.ACTIVE) {
            currentTabItems.forEach { TransferManager.cancel(it.id) }
        } else {
            currentTabItems.forEach { TransferManager.remove(it.id) }
        }
    }

    private fun deleteSelected() {
        if (currentTab == Tab.ACTIVE) {
            selectedIds.toList().forEach { TransferManager.cancel(it) }
        } else {
            selectedIds.toList().forEach { TransferManager.remove(it) }
        }
        exitSelectionMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
