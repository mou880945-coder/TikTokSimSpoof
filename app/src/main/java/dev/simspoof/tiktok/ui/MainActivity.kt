package dev.simspoof.tiktok.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.simspoof.tiktok.R
import dev.simspoof.tiktok.config.SimProfile
import dev.simspoof.tiktok.config.SpoofConfig
import dev.simspoof.tiktok.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentConfig = SpoofConfig()
    private var selectedProfile: SimProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentConfig = SpoofConfig.load(this)
        loadFieldsFromConfig(currentConfig)
        setupListeners()
        updateSelectedCountryButton(null)
        
        // 纯粹的界面汉化，不包含任何隐藏功能
        applyChineseTranslation()
    }

    private fun applyChineseTranslation() = with(binding) {
        // 1. 汉化开关
        switchEnabled.text = "启用模块"
        switchSpoofLocale.text = "伪装语言区域 (Locale)"

        // 2. 汉化所有输入框的提示文字（Hint）
        etSimCountryIso.hint = "SIM 国家代码 (例如: us)"
        etSimOperatorMccMnc.hint = "SIM 运营商 MCC+MNC (例如: 31026)"
        etSimOperatorName.hint = "SIM 运营商名称 (例如: T-Mobile)"
        
        etNetworkCountryIso.hint = "网络国家代码 (例如: us)"
        etNetworkOperatorMccMnc.hint = "网络运营商 MCC+MNC (例如: 31026)"
        etNetworkOperatorName.hint = "网络运营商名称 (例如: T-Mobile)"
        
        etLine1Number.hint = "手机号码 (留空 = 保持真实)"
        etSubscriberId.hint = "IMSI 订阅者 ID (可选)"

        etLocaleLanguage.hint = "语言 (例如: en)"
        etLocaleCountry.hint = "国家 (例如: US)"

        // 3. 汉化底部按钮
        btnSave.text = "保存配置"
        btnReset.text = "恢复默认"
    }

    private fun loadFieldsFromConfig(cfg: SpoofConfig) = with(binding) {
        switchEnabled.isChecked = cfg.enabled
        etSimCountryIso.setText(cfg.simCountryIso)
        etSimOperatorMccMnc.setText(cfg.simOperatorMccMnc)
        etSimOperatorName.setText(cfg.simOperatorName)
        etNetworkCountryIso.setText(cfg.networkCountryIso)
        etNetworkOperatorMccMnc.setText(cfg.networkOperatorMccMnc)
        etNetworkOperatorName.setText(cfg.networkOperatorName)
        etLine1Number.setText(cfg.line1Number)
        etSubscriberId.setText(cfg.subscriberId)
        switchSpoofLocale.isChecked = cfg.spoofLocale
        etLocaleLanguage.setText(cfg.localeLanguage)
        etLocaleCountry.setText(cfg.localeCountry)
        groupLocale.visibility = if (cfg.spoofLocale) View.VISIBLE else View.GONE
    }

    private fun buildConfigFromFields(): SpoofConfig = with(binding) {
        return SpoofConfig(
            enabled = switchEnabled.isChecked,
            simCountryIso = etSimCountryIso.text.toString().trim().lowercase(),
            simOperatorMccMnc = etSimOperatorMccMnc.text.toString().trim(),
            simOperatorName = etSimOperatorName.text.toString().trim(),
            networkCountryIso = etNetworkCountryIso.text.toString().trim().lowercase(),
            networkOperatorMccMnc = etNetworkOperatorMccMnc.text.toString().trim(),
            networkOperatorName = etNetworkOperatorName.text.toString().trim(),
            line1Number = etLine1Number.text.toString().trim(),
            subscriberId = etSubscriberId.text.toString().trim(),
            spoofLocale = switchSpoofLocale.isChecked,
            localeLanguage = etLocaleLanguage.text.toString().trim(),
            localeCountry = etLocaleCountry.text.toString().trim()
        )
    }

    private fun updateSelectedCountryButton(profile: SimProfile?) = with(binding) {
        if (profile == null) {
            btnCountryPicker.text = "🌍  选择国家预设..."
            tvSelectedCarrier.visibility = View.GONE
        } else {
            btnCountryPicker.text = "${profile.flag}  ${profile.countryName}"
            tvSelectedCarrier.text = "当前选择: ${profile.carrierName}  •  MCC/MNC: ${profile.config.simOperatorMccMnc}"
            tvSelectedCarrier.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() = with(binding) {
        btnCountryPicker.setOnClickListener { showCountryPickerDialog() }

        switchSpoofLocale.setOnCheckedChangeListener { _, checked ->
            groupLocale.visibility = if (checked) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            currentConfig = buildConfigFromFields()
            SpoofConfig.save(this@MainActivity, currentConfig)
            Toast.makeText(this@MainActivity,
                "✅ 配置已保存！请强行停止 TikTok 并重新打开。", Toast.LENGTH_LONG).show()
        }

        btnReset.setOnClickListener {
            currentConfig = SpoofConfig()
            SpoofConfig.save(this@MainActivity, currentConfig)
            loadFieldsFromConfig(currentConfig)
            selectedProfile = null
            updateSelectedCountryButton(null)
            Toast.makeText(this@MainActivity, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCountryPickerDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_country_picker)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etSearch = dialog.findViewById<EditText>(R.id.et_search_country)
        val rvCountries = dialog.findViewById<RecyclerView>(R.id.rv_countries)

        etSearch?.hint = "搜索国家、运营商或国家代码..."

        val allProfiles = SimProfile.entries.toList()
        val adapter = CountryAdapter(allProfiles) { profile ->
            selectedProfile = profile
            val cfg = profile.config.copy(
                enabled = binding.switchEnabled.isChecked,
                spoofLocale = true
            )
            loadFieldsFromConfig(cfg)
            updateSelectedCountryButton(profile)
            dialog.dismiss()
        }

        rvCountries.layoutManager = LinearLayoutManager(this)
        rvCountries.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val query = s.toString().trim().lowercase()
                val filtered = if (query.isEmpty()) allProfiles
                else allProfiles.filter {
                    it.countryName.lowercase().contains(query) ||
                            it.carrierName.lowercase().contains(query) ||
                            it.config.simCountryIso.lowercase().contains(query)
                }
                adapter.updateList(filtered)
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })

        dialog.show()
    }

    private inner class CountryAdapter(
        private var items: List<SimProfile>,
        private val onClick: (SimProfile) -> Unit
    ) : RecyclerView.Adapter<CountryAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvFlag: TextView = view.findViewById(R.id.tv_flag)
            val tvCountry: TextView = view.findViewById(R.id.tv_country)
            val tvCarrier: TextView = view.findViewById(R.id.tv_carrier)
            val tvMcc: TextView = view.findViewById(R.id.tv_mcc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_country, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.tvFlag.text = p.flag
            holder.tvCountry.text = p.countryName
            holder.tvCarrier.text = p.carrierName
            holder.tvMcc.text = p.config.simOperatorMccMnc
            holder.itemView.setOnClickListener { onClick(p) }
            val isSelected = p == selectedProfile
            holder.itemView.alpha = if (isSelected) 1f else 0.85f
            holder.tvCountry.setTextColor(
                if (isSelected) getColor(R.color.accent_green)
                else getColor(R.color.text_primary)
            )
        }

        override fun getItemCount() = items.size

        fun updateList(newItems: List<SimProfile>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
