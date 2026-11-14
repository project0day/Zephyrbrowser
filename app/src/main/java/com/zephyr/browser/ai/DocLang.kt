package com.zephyr.browser.ai

/**
 * v1.7.88: the headings that end up INSIDE a generated document.
 *
 * These are deliberately not string resources, and the distinction matters.
 * A resource follows the interface language — but the language of a document
 * is a property of the document, not of the app. The common case in this app is
 * precisely the one where they differ: a Russian-speaking autoentrepreneur in
 * France writes a French devis for a French client, from a browser they read in
 * Russian. Resources would have stamped "Опыт работы" onto a CV meant for a
 * French employer.
 *
 * So the language is chosen per document:
 *   · the assistant passes one when the context makes it obvious — a job advert
 *     in German, a client whose details are French;
 *   · otherwise it defaults to the interface language, which is right whenever
 *     the user is writing for their own country;
 *   · and the user can always just say which language they want.
 *
 * Unknown languages fall back to English rather than to the app's language: an
 * English heading in a Hungarian CV is a mild oddity, a Russian one is wrong.
 */
object DocLang {

    /** Every heading a generated CV or business document needs. */
    data class Words(
        val contacts: String,
        val skills: String,
        val languages: String,
        val experience: String,
        val education: String,
        val about: String,
        val position: String,
        val speciality: String,
        val present: String,
        val fullName: String
    )

    private val EN = Words(
        contacts = "Contact",
        skills = "Skills",
        languages = "Languages",
        experience = "Experience",
        education = "Education",
        about = "Profile",
        position = "Position",
        speciality = "Field of study",
        present = "present",
        fullName = "Full Name"
    )

    private val RU = Words(
        contacts = "Контакты",
        skills = "Навыки",
        languages = "Языки",
        experience = "Опыт работы",
        education = "Образование",
        about = "О себе",
        position = "Должность",
        speciality = "Специальность",
        present = "наст. время",
        fullName = "Имя Фамилия"
    )

    private val FR = Words(
        contacts = "Contact",
        skills = "Compétences",
        languages = "Langues",
        experience = "Expérience professionnelle",
        education = "Formation",
        about = "Profil",
        position = "Poste",
        speciality = "Spécialité",
        present = "aujourd’hui",
        fullName = "Prénom Nom"
    )

    private val DE = Words(
        contacts = "Kontakt",
        skills = "Kenntnisse",
        languages = "Sprachen",
        experience = "Berufserfahrung",
        education = "Ausbildung",
        about = "Profil",
        position = "Position",
        speciality = "Fachrichtung",
        present = "heute",
        fullName = "Vorname Nachname"
    )

    private val ES = Words(
        contacts = "Contacto",
        skills = "Competencias",
        languages = "Idiomas",
        experience = "Experiencia profesional",
        education = "Formación",
        about = "Perfil",
        position = "Puesto",
        speciality = "Especialidad",
        present = "actualidad",
        fullName = "Nombre Apellido"
    )

    private val IT = Words(
        contacts = "Contatti",
        skills = "Competenze",
        languages = "Lingue",
        experience = "Esperienza professionale",
        education = "Formazione",
        about = "Profilo",
        position = "Posizione",
        speciality = "Specializzazione",
        present = "presente",
        fullName = "Nome Cognome"
    )

    private val TABLE = mapOf(
        "en" to EN, "ru" to RU, "fr" to FR, "de" to DE, "es" to ES, "it" to IT
    )

    /** Languages a document can be produced in, for the tool schema. */
    val SUPPORTED: List<String> = TABLE.keys.sorted()

    /**
     * Headings for a document.
     *
     * A blank or unrecognised code means "the user did not say", and the
     * interface language is the best guess available.
     */
    fun words(code: String?): Words {
        val c = code?.trim()?.lowercase()?.take(2).orEmpty()
        TABLE[c]?.let { return it }
        return TABLE[AiLang.code()] ?: EN
    }

    /** The document language actually used, for reporting back to the model. */
    fun resolve(code: String?): String {
        val c = code?.trim()?.lowercase()?.take(2).orEmpty()
        if (TABLE.containsKey(c)) return c
        val ui = AiLang.code()
        return if (TABLE.containsKey(ui)) ui else "en"
    }
}
