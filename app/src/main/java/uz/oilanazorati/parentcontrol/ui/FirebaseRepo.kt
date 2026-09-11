package uz.oilanazorati.parentcontrol.ui

/**
 * MainActivity eski ui package ichida FirebaseRepo nomini ishlatgani uchun
 * kichik facade. Asosiy Firebase logika repo.FirebaseRepo ichida qoladi.
 */
object FirebaseRepo {
    var familyCode: String?
        get() = uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode
        set(value) {
            uz.oilanazorati.parentcontrol.repo.FirebaseRepo.familyCode = value
        }

    fun findOrCreateFamilyForCurrentUser(onResult: (String?) -> Unit) {
        uz.oilanazorati.parentcontrol.repo.FirebaseRepo.findOrCreateFamilyForCurrentUser(onResult)
    }
}
