package com.zephyr.browser.ai

/**
 * v1.7.67: three CVs a person would actually be glad to send.
 *
 * A model asked for "a beautiful CV" with no reference produces a safe, dull
 * one — the same way a person would. These are the reference. Each is a
 * complete page the assistant copies and fills in, not a fragment to assemble,
 * because a template that has to be assembled gets assembled wrong.
 *
 * Constraints baked into all three, learned from what breaks in practice:
 *
 *  - SYSTEM FONTS only. Rendering happens with the network blocked, so a web
 *    font would silently fall back and ruin the spacing it was chosen for;
 *  - print-color-adjust, so the colour survives the trip to PDF;
 *  - A4 with its own margins, and type sizes chosen so a normal career fits on
 *    ONE page — the user's complaint about two pages is a design problem, not
 *    a printing one;
 *  - no JavaScript, no external images. A photo arrives as a data: URI.
 */
object CvTemplates {

    val NAMES = listOf("sidebar", "classic", "minimal")

    fun describe(): String = """
        Доступные шаблоны резюме:
        • sidebar — цветная боковая колонка слева (контакты, навыки, языки),
          справа опыт и образование. Современный, заметный, хорош для IT,
          дизайна, маркетинга.
        • classic — строгий одноколоночный, тонкая цветная линия под именем.
          Подходит для банков, юристов, госструктур, консервативных компаний.
        • minimal — много воздуха, крупное имя, акцент одним цветом.
          Хорош, когда важен текст, а не оформление.
        Бери шаблон целиком через cv_template и заменяй только содержимое.
    """.trimIndent()

    fun get(name: String, lang: String = ""): String {
        val html = when (name.trim().lowercase()) {
            "classic" -> CLASSIC
            "minimal" -> MINIMAL
            else -> SIDEBAR
        }
        return localise(html, lang)
    }

    /**
     * v1.7.88: put the skeleton into the document's language.
     *
     * The model is told to take the template whole and replace only the
     * CONTENT, which means the headings survive verbatim — so a French CV came
     * out with «Опыт работы» over its job list. The prose inside is filler the
     * model rewrites anyway; the headings and the structural placeholders are
     * what has to be right, and those come from [DocLang].
     *
     * Replacements run longest-first: "Должность, на которую претендую"
     * contains "Должность", and swapping the short one first would leave a
     * half-translated line behind.
     */
    private fun localise(html: String, lang: String): String {
        val w = DocLang.words(lang)
        if (w === DocLang.words("ru") && DocLang.resolve(lang) == "ru") return html
        val pairs = listOf(
            "Должность, на которую претендую" to w.position,
            "Опыт работы" to w.experience,
            "Образование" to w.education,
            "Специальность" to w.speciality,
            "Должность" to w.position,
            "наст. время" to w.present,
            "Контакты" to w.contacts,
            "Навыки" to w.skills,
            "Языки" to w.languages,
            "О себе" to w.about,
            "Опыт" to w.experience,
            "ИМЯ ФАМИЛИЯ" to w.fullName.uppercase(),
            "Имя <b>Фамилия</b>" to w.fullName.substringBefore(' ') +
                " <b>" + w.fullName.substringAfter(' ', w.fullName) + "</b>"
        )
        var out = html
        for ((from, to) in pairs) out = out.replace(from, to)
        return out
    }

    private const val FONTS =
        "'Roboto','Noto Sans','Droid Sans','Helvetica Neue',Arial,sans-serif"

    /** Coloured left column. The accent is one variable, so it is easy to retheme. */
    private val SIDEBAR = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<style>
  :root{ --accent:#1f4e79; --accent-soft:#eaf1f8; --ink:#1c1c1e; --muted:#5b6470; }
  *{ box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  @page{ size:A4; margin:0; }
  html,body{ margin:0; padding:0; }
  body{ font-family:$FONTS; color:var(--ink); font-size:10.5pt; line-height:1.45; }
  .sheet{ width:210mm; min-height:297mm; display:flex; }
  .side{ width:68mm; background:var(--accent); color:#fff; padding:16mm 9mm; }
  .main{ flex:1; padding:16mm 12mm 14mm 11mm; }
  .photo{ width:38mm; height:38mm; border-radius:50%; object-fit:cover;
          display:block; margin:0 auto 7mm; border:3px solid rgba(255,255,255,.55); }
  .side h1{ font-size:17pt; margin:0 0 2mm; line-height:1.15; letter-spacing:.2px; }
  .side .role{ font-size:10pt; opacity:.9; margin:0 0 8mm; }
  .side h2{ font-size:9pt; text-transform:uppercase; letter-spacing:1.4px;
            margin:7mm 0 2.5mm; padding-bottom:1.5mm;
            border-bottom:1px solid rgba(255,255,255,.35); }
  .side p,.side li{ font-size:9.5pt; line-height:1.5; }
  .side ul{ margin:0; padding-left:4.5mm; }
  .contact div{ margin-bottom:1.6mm; word-break:break-word; }
  .bar{ height:3.6px; background:rgba(255,255,255,.28); border-radius:2px; margin:1.2mm 0 3mm; }
  .bar i{ display:block; height:100%; background:#fff; border-radius:2px; }
  .main h2{ font-size:11pt; color:var(--accent); text-transform:uppercase;
            letter-spacing:1.2px; margin:0 0 3mm; }
  .main section{ margin-bottom:7mm; }
  .job{ margin-bottom:5mm; }
  .job .top{ display:flex; justify-content:space-between; gap:4mm; align-items:baseline; }
  .job .title{ font-weight:700; font-size:11pt; }
  .job .when{ color:var(--muted); font-size:9pt; white-space:nowrap; }
  .job .where{ color:var(--accent); font-size:9.5pt; margin-bottom:1.2mm; }
  .job ul{ margin:1mm 0 0; padding-left:5mm; }
  .job li{ margin-bottom:.9mm; }
  .summary{ background:var(--accent-soft); border-left:3px solid var(--accent);
            padding:3.5mm 4mm; margin-bottom:7mm; font-size:10pt; }
</style></head>
<body><div class="sheet">
  <aside class="side">
    <!-- фото необязательно; вставляется как data:image/jpeg;base64,... -->
    <h1>ИМЯ ФАМИЛИЯ</h1>
    <p class="role">Должность, на которую претендую</p>

    <h2>Контакты</h2>
    <div class="contact">
      <div>+33 6 00 00 00 00</div>
      <div>mail@example.com</div>
      <div>Город, страна</div>
    </div>

    <h2>Навыки</h2>
    <div>Навык один<div class="bar"><i style="width:90%"></i></div></div>
    <div>Навык два<div class="bar"><i style="width:75%"></i></div></div>

    <h2>Языки</h2>
    <ul><li>Русский — родной</li><li>Французский — B2</li></ul>
  </aside>

  <main class="main">
    <div class="summary">Два-три предложения: кто вы, сколько лет опыта и что
      приносите работодателю. Здесь пишутся слова из объявления о вакансии.</div>

    <section>
      <h2>Опыт работы</h2>
      <div class="job">
        <div class="top"><span class="title">Должность</span><span class="when">2022 — наст. время</span></div>
        <div class="where">Компания, город</div>
        <ul>
          <li>Достижение с цифрой: увеличил / сократил / запустил.</li>
          <li>Второе достижение, тоже измеримое.</li>
        </ul>
      </div>
    </section>

    <section>
      <h2>Образование</h2>
      <div class="job">
        <div class="top"><span class="title">Специальность</span><span class="when">2018 — 2022</span></div>
        <div class="where">Учебное заведение</div>
      </div>
    </section>
  </main>
</div></body></html>
""".trimIndent()

    /** One column, restrained, for conservative employers. */
    private val CLASSIC = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<style>
  :root{ --accent:#8a1c1c; --ink:#16181d; --muted:#5d6472; }
  *{ box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  @page{ size:A4; margin:0; }
  html,body{ margin:0; }
  body{ font-family:$FONTS; color:var(--ink); font-size:10.5pt; line-height:1.5; }
  .sheet{ width:210mm; min-height:297mm; padding:17mm 18mm; }
  header{ border-bottom:2.5px solid var(--accent); padding-bottom:4mm; margin-bottom:6mm; }
  h1{ font-size:21pt; margin:0 0 1.5mm; letter-spacing:.4px; }
  .role{ color:var(--accent); font-size:11pt; margin:0 0 2.5mm; }
  .contact{ color:var(--muted); font-size:9.5pt; }
  .contact span{ margin-right:5mm; white-space:nowrap; }
  h2{ font-size:10.5pt; text-transform:uppercase; letter-spacing:1.5px;
      color:var(--accent); margin:6.5mm 0 2.5mm; }
  section{ margin-bottom:2mm; }
  .job{ margin-bottom:4.5mm; }
  .top{ display:flex; justify-content:space-between; gap:4mm; align-items:baseline; }
  .title{ font-weight:700; }
  .when{ color:var(--muted); font-size:9.5pt; white-space:nowrap; }
  .where{ font-style:italic; color:var(--muted); font-size:9.5pt; }
  ul{ margin:1.2mm 0 0; padding-left:5mm; }
  li{ margin-bottom:1mm; }
  .skills{ display:flex; flex-wrap:wrap; gap:2mm; }
  .skills span{ border:1px solid var(--accent); color:var(--accent);
                border-radius:3px; padding:.8mm 2.6mm; font-size:9pt; }
</style></head>
<body><div class="sheet">
  <header>
    <h1>ИМЯ ФАМИЛИЯ</h1>
    <p class="role">Должность</p>
    <div class="contact">
      <span>+33 6 00 00 00 00</span><span>mail@example.com</span><span>Город</span>
    </div>
  </header>

  <section><h2>О себе</h2>
    <p>Два-три предложения под конкретную вакансию.</p></section>

  <section><h2>Опыт работы</h2>
    <div class="job">
      <div class="top"><span class="title">Должность</span><span class="when">2022 — наст. время</span></div>
      <div class="where">Компания, город</div>
      <ul><li>Измеримое достижение.</li><li>Ещё одно.</li></ul>
    </div>
  </section>

  <section><h2>Образование</h2>
    <div class="job">
      <div class="top"><span class="title">Специальность</span><span class="when">2018 — 2022</span></div>
      <div class="where">Учебное заведение</div>
    </div>
  </section>

  <section><h2>Навыки</h2>
    <div class="skills"><span>Навык</span><span>Навык</span><span>Навык</span></div>
  </section>

  <section><h2>Языки</h2><p>Русский — родной · Французский — B2</p></section>
</div></body></html>
""".trimIndent()

    /** Air and one accent colour. */
    private val MINIMAL = """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<style>
  :root{ --accent:#0f9d76; --ink:#1a1a1a; --muted:#71787f; }
  *{ box-sizing:border-box; -webkit-print-color-adjust:exact; print-color-adjust:exact; }
  @page{ size:A4; margin:0; }
  html,body{ margin:0; }
  body{ font-family:$FONTS; color:var(--ink); font-size:10.5pt; line-height:1.55; }
  .sheet{ width:210mm; min-height:297mm; padding:20mm 20mm 16mm; }
  h1{ font-size:30pt; font-weight:300; letter-spacing:-.6px; margin:0; }
  h1 b{ font-weight:700; }
  .role{ color:var(--accent); font-size:11.5pt; letter-spacing:2.5px;
         text-transform:uppercase; margin:2mm 0 4mm; }
  .contact{ color:var(--muted); font-size:9.5pt; margin-bottom:9mm; }
  .contact span::after{ content:" · "; }
  .contact span:last-child::after{ content:""; }
  .row{ display:flex; gap:7mm; margin-bottom:6mm; }
  .row .label{ width:32mm; flex:none; color:var(--accent); font-size:9pt;
               text-transform:uppercase; letter-spacing:1.3px; padding-top:1mm; }
  .row .body{ flex:1; }
  .job{ margin-bottom:4.5mm; }
  .title{ font-weight:700; }
  .when{ color:var(--muted); font-size:9.5pt; }
  ul{ margin:1.2mm 0 0; padding-left:4.5mm; }
  li{ margin-bottom:.9mm; }
  hr{ border:0; border-top:1px solid #e6e8ea; margin:0 0 6mm; }
</style></head>
<body><div class="sheet">
  <h1>Имя <b>Фамилия</b></h1>
  <p class="role">Должность</p>
  <div class="contact">
    <span>+33 6 00 00 00 00</span><span>mail@example.com</span><span>Город</span>
  </div>
  <hr>

  <div class="row"><div class="label">О себе</div>
    <div class="body">Два-три предложения под вакансию.</div></div>

  <div class="row"><div class="label">Опыт</div>
    <div class="body">
      <div class="job">
        <div class="title">Должность — Компания</div>
        <div class="when">2022 — наст. время</div>
        <ul><li>Измеримое достижение.</li><li>Ещё одно.</li></ul>
      </div>
    </div></div>

  <div class="row"><div class="label">Образование</div>
    <div class="body"><div class="job">
      <div class="title">Специальность</div>
      <div class="when">Учебное заведение, 2018 — 2022</div>
    </div></div></div>

  <div class="row"><div class="label">Навыки</div>
    <div class="body">Навык · Навык · Навык</div></div>

  <div class="row"><div class="label">Языки</div>
    <div class="body">Русский — родной · Французский — B2</div></div>
</div></body></html>
""".trimIndent()
}
