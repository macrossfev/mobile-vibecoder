package com.vibecoder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibecoder.R
import com.vibecoder.data.PreferencesManager
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentServerListBinding
import com.vibecoder.databinding.DialogAddServerBinding
import com.vibecoder.ssh.SSHKeyGenerator
import kotlinx.coroutines.launch

class ServerListFragment : Fragment() {

    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefsManager: PreferencesManager
    private lateinit var serverAdapter: ServerAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())

        setupRecyclerView()
        setupFab()
        loadServers()
    }

    private fun setupRecyclerView() {
        serverAdapter = ServerAdapter(
            onServerClick = { server -> openTerminal(server) },
            onServerLongClick = { server -> showServerOptions(server) },
            onMonitorClick = { server -> openMonitor(server) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = serverAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddServer.setOnClickListener { showAddServerDialog() }
        // 移除语音按钮功能
    }

    private fun loadServers() {
        val servers = prefsManager.getServers()
        serverAdapter.submitList(servers)
        binding.emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddServerDialog() {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater)

        // 设置认证方式切换
        dialogBinding.rgAuthType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                dialogBinding.rbPassword.id -> {
                    dialogBinding.tilPassword.visibility = View.VISIBLE
                    dialogBinding.llKeyAuth.visibility = View.GONE
                }
                dialogBinding.rbKey.id -> {
                    dialogBinding.tilPassword.visibility = View.GONE
                    dialogBinding.llKeyAuth.visibility = View.VISIBLE
                }
            }
        }

        // 生成密钥按钮
        dialogBinding.btnGenerateKey.setOnClickListener {
            showKeyGenerationDialog(dialogBinding)
        }

        // 粘贴密钥按钮
        dialogBinding.btnPasteKey.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.contains("PRIVATE KEY")) {
                    dialogBinding.etPrivateKey.setText(text)
                    Toast.makeText(requireContext(), "已粘贴私钥", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "剪贴板中没有有效的私钥", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 复制公钥按钮
        dialogBinding.btnCopyPublicKey.setOnClickListener {
            val publicKey = dialogBinding.tvPublicKey.text.toString()
            if (publicKey.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Public Key", publicKey)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "公钥已复制", Toast.LENGTH_SHORT).show()
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加服务器")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val isKeyAuth = dialogBinding.rbKey.isChecked
                val server = ServerConfig(
                    name = dialogBinding.etName.text.toString(),
                    host = dialogBinding.etHost.text.toString(),
                    port = dialogBinding.etPort.text.toString().toIntOrNull() ?: 22,
                    username = dialogBinding.etUsername.text.toString(),
                    password = if (isKeyAuth) null else dialogBinding.etPassword.text.toString().ifBlank { null },
                    privateKey = if (isKeyAuth) dialogBinding.etPrivateKey.text.toString().ifBlank { null } else null,
                    passphrase = if (isKeyAuth) dialogBinding.etPassphrase.text.toString().ifBlank { null } else null
                )
                prefsManager.addServer(server)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showKeyGenerationDialog(dialogBinding: DialogAddServerBinding) {
        val keyTypes = SSHKeyGenerator.getSupportedKeyTypes().map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择密钥类型")
            .setItems(keyTypes) { _, which ->
                val selectedType = SSHKeyGenerator.getSupportedKeyTypes()[which]
                generateKeyPair(selectedType, dialogBinding)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun generateKeyPair(keyType: SSHKeyGenerator.KeyType, dialogBinding: DialogAddServerBinding) {
        lifecycleScope.launch {
            try {
                val keyPair = SSHKeyGenerator.generateKeyPair(keyType)

                // 填充私钥
                dialogBinding.etPrivateKey.setText(keyPair.privateKey)

                // 显示公钥
                dialogBinding.tvPublicKey.text = keyPair.publicKey
                dialogBinding.llPublicKey.visibility = View.VISIBLE

                Toast.makeText(
                    requireContext(),
                    "密钥已生成\n指纹: ${keyPair.fingerprint}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showServerOptions(server: ServerConfig) {
        val options = arrayOf("编辑", "删除", "复制连接信息")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(server.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editServer(server)
                    1 -> deleteServer(server)
                    2 -> copyServerInfo(server)
                }
            }
            .show()
    }

    private fun editServer(server: ServerConfig) {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater).apply {
            etName.setText(server.name)
            etHost.setText(server.host)
            etPort.setText(server.port.toString())
            etUsername.setText(server.username)

            // 根据认证方式设置UI
            if (server.privateKey != null) {
                rbKey.isChecked = true
                tilPassword.visibility = View.GONE
                llKeyAuth.visibility = View.VISIBLE
                etPrivateKey.setText(server.privateKey)
                etPassphrase.setText(server.passphrase)
            } else {
                rbPassword.isChecked = true
                tilPassword.visibility = View.VISIBLE
                llKeyAuth.visibility = View.GONE
                etPassword.setText(server.password)
            }

            // 设置认证方式切换
            rgAuthType.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    rbPassword.id -> {
                        tilPassword.visibility = View.VISIBLE
                        llKeyAuth.visibility = View.GONE
                    }
                    rbKey.id -> {
                        tilPassword.visibility = View.GONE
                        llKeyAuth.visibility = View.VISIBLE
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑服务器")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val isKeyAuth = dialogBinding.rbKey.isChecked
                val updated = server.copy(
                    name = dialogBinding.etName.text.toString(),
                    host = dialogBinding.etHost.text.toString(),
                    port = dialogBinding.etPort.text.toString().toIntOrNull() ?: 22,
                    username = dialogBinding.etUsername.text.toString(),
                    password = if (isKeyAuth) null else dialogBinding.etPassword.text.toString().ifBlank { null },
                    privateKey = if (isKeyAuth) dialogBinding.etPrivateKey.text.toString().ifBlank { null } else null,
                    passphrase = if (isKeyAuth) dialogBinding.etPassphrase.text.toString().ifBlank { null } else null
                )
                prefsManager.updateServer(updated)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteServer(server: ServerConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除服务器 \"${server.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                prefsManager.deleteServer(server.id)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyServerInfo(server: ServerConfig) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Server Info", "${server.username}@${server.host}:${server.port}")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun openTerminal(server: ServerConfig) {
        val fragment = TerminalFragment.newInstance(server)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openMonitor(server: ServerConfig) {
        val fragment = MonitorFragment.newInstance(server)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}