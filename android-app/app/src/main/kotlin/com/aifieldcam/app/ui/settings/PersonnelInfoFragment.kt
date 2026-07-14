package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.OfficerProfile
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentPersonnelInfoBinding
import com.aifieldcam.app.databinding.ItemProfileInfoRowBinding
import com.aifieldcam.app.platform.DeviceIdentity

/** 已绑定：只读资料；未绑定：引导回「我的」扫码。 */
class PersonnelInfoFragment : Fragment() {

    private var _binding: FragmentPersonnelInfoBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPersonnelInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.tvTitle.text = getString(R.string.me_personnel)
        binding.header.tvTitle.setTextColor(resources.getColor(R.color.home_text_primary, null))
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }
        binding.btnGoScan.setOnClickListener {
            (parentFragment as? MeFragment)?.popToMeHub()
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        if (_binding == null) return
        val bound = session.isDeviceBound()
        binding.panelUnbound.visibility = if (bound) View.GONE else View.VISIBLE
        binding.panelProfile.visibility = if (bound) View.VISIBLE else View.GONE
        if (bound) {
            bindProfile(session.getSavedOfficerProfile())
        }
    }

    private fun bindProfile(profile: OfficerProfile?) {
        val name = profile?.name?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvProfileHeroName.text = name
        binding.tvProfileHeroLetter.text = name.firstOrNull()?.toString().orEmpty()
        binding.tvProfileHeroEmployee.text = getString(
            R.string.profile_employee_id_format,
            profile?.employeeId?.takeIf { it.isNotBlank() } ?: "—",
        )
        binding.tvProfileStatusChip.text = getString(R.string.profile_status_verified)
        bindRow(binding.rowPhone, R.string.profile_label_phone, profile?.phone.orEmpty().ifBlank { "—" })
        bindRow(binding.rowGender, R.string.profile_label_gender, profile?.gender.orEmpty().ifBlank { "—" })
        bindRow(binding.rowIdCard, R.string.profile_label_id_card, maskId(profile?.idCard.orEmpty()))
        bindRow(binding.rowCompany, R.string.profile_label_company, profile?.company.orEmpty().ifBlank { "—" })
        bindRow(binding.rowDepartment, R.string.profile_label_department, profile?.department.orEmpty().ifBlank { "—" })
        bindRow(binding.rowPosition, R.string.profile_label_position, profile?.position.orEmpty().ifBlank { "—" })
        val device = profile?.deviceId?.takeIf { it.isNotBlank() }
            ?: DeviceIdentity.recorderId(requireContext())
        bindRow(binding.rowDevice, R.string.profile_label_device, device)
    }

    private fun bindRow(row: ItemProfileInfoRowBinding, labelRes: Int, value: String) {
        row.tvLabel.text = getString(labelRes)
        row.tvValue.text = value
        row.tvLabel.setTextColor(resources.getColor(R.color.home_text_secondary, null))
        row.tvValue.setTextColor(resources.getColor(R.color.home_text_primary, null))
    }

    private fun maskId(idCard: String): String {
        if (idCard.length < 8) return idCard.ifBlank { "—" }
        return idCard.take(4) + "**********" + idCard.takeLast(4)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = PersonnelInfoFragment()
    }
}
