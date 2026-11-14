package com.zephyr.browser.ai

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zephyr.browser.MainActivity
import com.zephyr.browser.Prefs
import com.zephyr.browser.R
import com.zephyr.browser.db.AppDb
import com.zephyr.browser.db.BookmarkEntity
import com.zephyr.browser.tasks.Reminders
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7.41: the assistant stops being a chat box and starts DOING things.
 *
 * The tool loop was already built for `web_search` / `open_page`, so every new
 * capability is just a definition plus a branch — the expensive part was done.
 * Three families arrived together:
 *
 *  - PAGE AUTOMATION — read, extract, click, fill, scroll. This is what turns
 *    "what do they sell here?" into "open it, scroll it, build a table of prices";
 *  - BROWSER — open a tab, run a search, list or close tabs;
 *  - YOUR DATA — history, bookmarks, downloads and files, searchable in words
 *    instead of by scrolling.
 *
 * Safety is by category, not by trust in the model:
 *  - reading (page text, history, bookmarks) runs silently;
 *  - navigating (open a tab, search) runs silently;
 *  - ACTING (click, fill, bookmark, close a tab) asks first, every time, until
 *    the user turns that off deliberately.
 * Passwords and payment cards are not reachable from here at all — no tool
 * touches those tables.
 */
object AiTools {

    /** Set while the browser is alive; tools are inert without it. */
    @Volatile
    var host: MainActivity? = null

    private const val MAX_TEXT = 8000
    private const val MAX_ROWS = 40

    // ---------------- definitions ----------------

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "function").put(
            "function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", JSONArray().apply { required.forEach { put(it) } })
                )
        )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int_(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    /**
     * v1.7.97: is any browser tool group switched on?
     *
     * Asked before a request, to decide whether the `tools` field is worth
     * sending at all. `host` is part of the answer: the tools act through the
     * browser activity, and with none attached every one of them would fail.
     */
    fun anyEnabled(): Boolean =
        host != null && (Prefs.aiToolsPage || Prefs.aiToolsBrowser || Prefs.aiToolsData ||
            Prefs.aiToolsScripts || Prefs.aiToolsModels)

    /** Tool definitions offered to the model, honouring the user's toggles. */
    fun specs(): JSONArray {
        val arr = JSONArray()
        if (host == null) return arr

        if (Prefs.aiToolsPage) {
            arr.put(fn("page_text", "Text of the open page.",
                JSONObject(), emptyList()))
            arr.put(fn(
                "page_extract",
                "Data from the page by CSS selector: text or an attribute. For lists, prices, links.",
                JSONObject()
                    .put("selector", str("CSS selector"))
                    .put("attribute", str("Attribute, for example href"))
                    .put("limit", int_("How many to return, 40 by default")),
                listOf("selector")
            ))
            arr.put(fn(
                "page_click",
                "Clicks an element by CSS selector.",
                JSONObject().put("selector", str("CSS selector of the element")),
                listOf("selector")
            ))
            arr.put(fn(
                "page_fill",
                "Types a value into a form field by CSS selector.",
                JSONObject()
                    .put("selector", str("CSS selector of the input"))
                    .put("value", str("What to type")),
                listOf("selector", "value")
            ))
            arr.put(fn(
                "page_scroll",
                "Scrolls the page — loads a lazy feed.",
                JSONObject().put("to", str("bottom, top, or a number of pixels")),
                listOf("to")
            ))
            // v1.7.55 (user: "I am on a foreign site and do not understand the language —
            // could the AI register or fill the fields itself?"). page_fill needs a
            // selector, and guessing one from a page in a language the model is
            // skimming is how it fills the wrong box. This hands it the form.
            arr.put(fn(
                "page_forms",
                "Form fields on the page: a ready CSS selector, the type, the label or placeholder, " +
                    "whether it is required, the options for dropdowns, and the submit buttons. " +
                    "Call this BEFORE page_fill, so you fill fields by what they actually mean " +
                    "instead of guessing selectors.",
                JSONObject().put("limit", int_("How many fields to return, 40 by default")),
                emptyList()
            ))
            arr.put(fn(
                "page_screenshot",
                "A shot of the visible part of the page — it arrives as an image in the next " +
                    "message. Use it when what matters is not the text but how the page LOOKS: " +
                    "diagrams, charts, layout, pictures. Only call it if you can read images.",
                JSONObject(), emptyList()
            ))
        }


        // v1.7.60 (user: "we support scripts — let the AI create any it likes,
        // and let them actually run").
        //
        // This is where the "scripts cannot run on Android" limit stops
        // applying: a userscript is not a file the OS executes, it is JS the
        // browser injects into a page. The engine, the matching and the manage
        // screen already exist — the assistant only needed a way in.
        if (Prefs.aiToolsScripts) {
            arr.put(fn(
                "run_js",
                "Runs JavaScript on the open page ONCE and returns the result. " +
                    "For one-off jobs: calculating something, collecting data, fixing layout. " +
                    "The last expression in the code is the value that comes back.",
                JSONObject().put("code", str("JavaScript code")),
                listOf("code")
            ))
            arr.put(fn(
                "add_userscript",
                "Creates a PERMANENT user script: it runs automatically every time a matching " +
                    "site is opened. The user confirms it by hand. Write plain JavaScript, " +
                    "with no wrapper.",
                JSONObject()
                    .put("name", str("Script name"))
                    .put("code", str("JavaScript code"))
                    .put("domains", str("Where to run: * for every site, or example.com, or several separated by commas")),
                listOf("name", "code")
            ))
            arr.put(fn("list_userscripts", "User scripts: number, name, where it runs, whether it is enabled.",
                JSONObject(), emptyList()))
            arr.put(fn("toggle_userscript", "Enables or disables a script by number.",
                JSONObject()
                    .put("id", int_("Script number"))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "true to enable, false to disable")),
                listOf("id", "enabled")))
            arr.put(fn("delete_userscript", "Deletes a script by number. The user confirms by hand.",
                JSONObject().put("id", int_("Script number")), listOf("id")))
        }
        if (Prefs.aiToolsBrowser) {
            arr.put(fn("open_tab", "Opens an address in a new tab.",
                JSONObject().put("url", str("Full address")), listOf("url")))
            arr.put(fn("browser_search", "Searches in the browser.",
                JSONObject().put("query", str("Search query")), listOf("query")))
            arr.put(fn("list_tabs", "Open tabs: index, title, address.",
                JSONObject(), emptyList()))
            arr.put(fn("close_tab", "Closes a tab by index.",
                JSONObject().put("index", int_("Tab index")), listOf("index")))
        }

        if (Prefs.aiToolsData) {
            arr.put(fn("search_history", "Searches the browsing history.",
                JSONObject()
                    .put("query", str("A word or part of an address"))
                    .put("limit", int_("How many entries, 20 by default")),
                listOf("query")))
            arr.put(fn("search_bookmarks", "Searches the bookmarks. An empty query returns all of them.",
                JSONObject().put("query", str("A word or part of an address")), emptyList()))
            arr.put(fn("add_bookmark", "Adds a bookmark.",
                JSONObject()
                    .put("url", str("Address"))
                    .put("title", str("Title")),
                listOf("url")))
            arr.put(fn("list_downloads", "List of downloads: name, size, path.",
                JSONObject().put("limit", int_("How many entries, 20 by default")), emptyList()))
            arr.put(fn("find_file", "Finds a file by part of its name.",
                JSONObject()
                    .put("name", str("Part of the file name"))
                    .put("limit", int_("How many results, 20 by default")),
                listOf("name")))
            arr.put(fn(
                "find_everything",
                "Searches everywhere in ONE call: history, bookmarks, downloads, files on the " +
                    "phone, tasks, what is remembered about the user, workflows and open tabs. " +
                    "Use this instead of four separate searches when asked \"what do I have " +
                    "about …\".",
                JSONObject().put("query", str("A word or a topic")),
                listOf("query")
            ))
            arr.put(fn("read_file", "Reads a text file at a full path.",
                JSONObject().put("path", str("Full path to the file")), listOf("path")))
        }

        // v1.7.57: memory that outlives the chat. Gated by its own switch, and
        // the description is explicit about WHAT belongs here — a model left to
        // guess saves the current task and pollutes every later answer.
        if (Prefs.aiMemoryOn) {
            arr.put(fn(
                "remember_fact",
                "Remembers a LASTING fact about the user, across every chat: their name, what " +
                    "they write code in, what they use, how they prefer to work. " +
                    "Do NOT store the current task, intermediate results, or anything that is " +
                    "only true today.",
                JSONObject().put("fact", str("One short fact")),
                listOf("fact")
            ))
            arr.put(fn("list_memory", "What is already remembered about the user, numbered.",
                JSONObject(), emptyList()))
            arr.put(fn("forget_fact", "Forgets a fact by its number from list_memory.",
                JSONObject().put("id", int_("Fact number")), listOf("id")))
        }

        // v1.7.61: the assistant can write the user a command. No new runtime —
        // a scenario is an instruction, and running one means sending it back
        // through this same tool loop.
        arr.put(fn(
            "save_workflow",
            "Saves a WORKFLOW — a named command belonging to the user. They can then run it " +
                "with one tap or put it on a schedule. In `prompt`, write a complete " +
                "instruction to yourself: what to do, with which files or sites, and in what " +
                "form to present the result. Keep the name to one or two words.",
            JSONObject()
                .put("name", str("Short command name, for example \"invoices\""))
                .put("prompt", str("The full instruction to carry out when it runs")),
            listOf("name", "prompt")
        ))
        arr.put(fn("list_workflows", "The user's saved workflows: name and what each one does.",
            JSONObject(), emptyList()))
        arr.put(fn("delete_workflow", "Deletes a workflow by name.",
            JSONObject().put("name", str("Workflow name")), listOf("name")))
        if (Prefs.aiToolsModels) {
            arr.put(fn(
                "list_ai_models",
                "Model profiles: name, address, model, whether a key is set. Keys are never returned.",
                JSONObject(), emptyList()
            ))
            arr.put(fn(
                "add_ai_model",
                "Adds a model profile. The user confirms it by hand.",
                JSONObject()
                    .put("name", str("Name"))
                    .put("endpoint", str("API address"))
                    .put("model", str("Model identifier"))
                    .put("key", str("API key")),
                listOf("name", "endpoint", "model")
            ))
            arr.put(fn(
                "use_ai_model",
                "Makes the given profile active (by its name from list_ai_models).",
                JSONObject().put("name", str("Profile name")), listOf("name")
            ))
        }

        // v1.7.54: writing code on the phone. Descriptions say plainly that a
        // script is SAVED, not run — Android will not execute a file the app
        // wrote, and a model told otherwise will promise the user a run.
        if (Prefs.aiToolsWrite) {
            arr.put(fn(
                "write_file",
                "Creates or overwrites a text file (code, a script, a note) in the workspace " +
                    AiWorkspace.rootPath() + ". The path may be relative; folders are created " +
                    "as needed. The file is SAVED, not run: Android does not execute code an " +
                    "app has written.",
                JSONObject()
                    .put("path", str("Path, for example project/main.py"))
                    .put("content", str("The complete file contents"))
                    .put("append", JSONObject().put("type", "boolean")
                        .put("description", "true to append to the end, otherwise overwrite")),
                listOf("path", "content")
            ))
            arr.put(fn("make_dir", "Creates a folder in the assistant's workspace.",
                JSONObject().put("path", str("Folder path, for example project/src")), listOf("path")))
            arr.put(fn("list_dir", "Folder contents: names and sizes. An empty path means the workspace root.",
                JSONObject().put("path", str("Folder path")), emptyList()))
            arr.put(fn("copy_path", "Copies a file or folder inside the workspace.",
                JSONObject()
                    .put("from", str("What to copy"))
                    .put("to", str("Where to — the full destination path")),
                listOf("from", "to")))
            arr.put(fn("move_path", "Moves or renames a file or folder.",
                JSONObject()
                    .put("from", str("What to move"))
                    .put("to", str("The new path")),
                listOf("from", "to")))
            arr.put(fn("delete_path", "Deletes a file or folder. The user confirms by hand.",
                JSONObject().put("path", str("Path of the file or folder")), listOf("path")))
            arr.put(fn(
                "zip_project",
                "Packs a folder, a file, OR several paths at once into a zip. For several " +
                    "files list them comma-separated in `path` — there is no need to gather " +
                    "them into one folder first.",
                JSONObject()
                    .put("path", str("What to pack: project, or a.md,b.md,notes/c.md"))
                    .put("name", str("Archive name without a path, optional")),
                listOf("path")
            ))
        }

        // v1.7.54: tasks and rendezvous. The current local time goes INTO the
        // description because the model has no clock — without it "tomorrow at 9"
        // is unanswerable and it invents a date.

        // v1.7.61 (user: "so the assistant can pull data out of a PDF, fill it in,
        // sign and date it — I downloaded Adobe and ticked the boxes by hand").
        //
        // Gated by the write switch: reading a PDF is harmless, but filling one
        // produces a file, and that belongs to the same permission as any other
        // file the assistant creates.
        if (Prefs.aiToolsWrite) {
            arr.put(fn(
                "pdf_read",
                "Reads the text of a PDF and reports whether it has fillable form fields.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("from_page", int_("Starting page, 1 by default"))
                    .put("to_page", int_("Last page, 0 means to the end")),
                listOf("path")
            ))
            arr.put(fn(
                "pdf_fields",
                "Lists the fillable fields of a PDF: the exact field name, its type (text, " +
                    "checkbox, radio, choice), its current value and the allowed options. " +
                    "Call this BEFORE pdf_fill — take field names from here rather than " +
                    "guessing them.",
                JSONObject().put("path", str("Path to the PDF")),
                listOf("path")
            ))
            arr.put(fn(
                "pdf_fill",
                "Fills the form fields and saves a NEW file beside the original, which is left " +
                    "untouched. `values` is an object {field name: value}. For checkboxes the " +
                    "value is on or off. flatten=true stamps the values in permanently so they " +
                    "can no longer be edited.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("values", JSONObject().put("type", "object")
                        .put("description", "{field name: value}"))
                    .put("flatten", JSONObject().put("type", "boolean")
                        .put("description", "true to stamp the values in permanently")),
                listOf("path", "values")
            ))
            arr.put(fn(
                "save_template",
                "Saves a TEMPLATE — a finished document, or a mini-application in HTML, that is " +
                    "later filled in from a single sentence. This is the main tool for documents " +
                    "the user produces again and again: an invoice, a quote, a contract, a " +
                    "delivery note, a proposal.\n" +
                    "Put placeholders where the text changes from one document to the next: " +
                    "{{client_name}}, {{amount}}, {{number}}, {{date}}. Do NOT ask for the user's " +
                    "own business details, the document number or the date — the app fills those " +
                    "in: {{company}}, {{siret}}, {{address}}, {{iban}}, {{number}}, {{date}}. " +
                    "business_profile returns the full list.\n" +
                    "kind: document — printed to PDF; app — opened in a tab as a mini-application " +
                    "(JavaScript is allowed in that case).",
                JSONObject()
                    .put("name", str("Short name, for example devis"))
                    .put("category", str("Folder: Commerce, Documents, Letters, Apps"))
                    .put("kind", str("document or app"))
                    .put("html", str("The complete HTML, with placeholders"))
                    .put("note", str("What this template is, in one line")),
                listOf("name", "html")
            ))
            arr.put(fn("list_templates", "Saved templates: name, folder, kind, and which fields each one needs.",
                JSONObject().put("category", str("Only from this folder, optional")), emptyList()))
            arr.put(fn(
                "use_template",
                "Fills a template in and saves the finished PDF, or opens the mini-application. " +
                    "`values` is an object {placeholder: value}. The business details, the date " +
                    "and the document number do not need to be supplied. If a field is left " +
                    "empty, the tool says so.",
                JSONObject()
                    .put("name", str("Template name"))
                    .put("values", JSONObject().put("type", "object").put("description", "{placeholder: value}"))
                    .put("path", str("Where to save it, without an extension; empty means beside the template"))
                    .put("number_kind", str("Document kind for numbering: devis, facture… {{number}} then gets the next number"))
                    .put("sign", JSONObject().put("type", "boolean")
                        .put("description", "true to add the user's signature. The signature " +
                            "block is appended automatically, even when the template has none")),
                listOf("name")
            ))
            arr.put(fn("delete_template", "Deletes a template by name.",
                JSONObject().put("name", str("Template name")), listOf("name")))
            arr.put(fn(
                "business_profile",
                "The user's business details and the next document number. Call this BEFORE " +
                    "creating an invoice or a quote, so you do not ask for what is already known.",
                JSONObject().put("number_kind", str("Document kind, to look up the next number")),
                emptyList()
            ))
            arr.put(fn(
                "make_cv",
                "The MAIN tool for a CV. You supply only the DATA; the app does the layout — " +
                    "which is fast and cannot break. Do NOT write HTML.\n" +
                    "When `template` is omitted, the user is asked to pick a layout and a colour " +
                    "from a picker with previews.\n" +
                    "`data` is an object: name, role, photo (data:image, optional), summary, " +
                    "contacts (list), skills (list; \"Skill|80\" gives a level bar), languages " +
                    "(list), experience and education — lists of objects " +
                    "{title, where, when, points: [strings]}.\n" +
                    "Write achievements with numbers in them. Keep it to one page.",
                JSONObject()
                    .put("path", str("Path without an extension, for example CV/Plumber-Lyon"))
                    .put("data", JSONObject().put("type", "object").put("description", "The CV data"))
                    .put("template", str("sidebar, classic, minimal — or empty to ask the user"))
                    .put("accent", str("Colour: blue, teal, green, purple, red, orange, graphite"))
                    .put("lang", str("Language of the DOCUMENT: " + DocLang.SUPPORTED.joinToString(", ") +
                        ". Use the language the CV will be read in — the language of the job " +
                        "advert or of the employer's country, not the interface language. " +
                        "Empty means the interface language.")),
                listOf("path", "data")
            ))
            arr.put(fn(
                "cv_template",
                "Returns a complete CV TEMPLATE (HTML+CSS): sidebar, classic or minimal. " +
                    "With no name, a short description of all three. Take the template and " +
                    "replace its content rather than rewriting the styling from scratch.",
                JSONObject()
                    .put("name", str("sidebar, classic or minimal"))
                    .put("lang", str("Language of the DOCUMENT: " + DocLang.SUPPORTED.joinToString(", ") +
                        ". The language of whoever reads the CV, not the interface. " +
                        "Empty means the interface language.")),
                emptyList()
            ))
            arr.put(fn(
                "render_html",
                "Lays out HTML and saves it AS A PROPERLY DESIGNED PDF, through the browser's " +
                    "own engine — so colours, columns, rules, fonts and photographs all work. " +
                    "An .html file is saved alongside it so the user can look and ask for " +
                    "changes.\n" +
                    "THIS IS HOW A CV SHOULD BE MADE: take cv_template, put the data in, send it " +
                    "here. Do not build a CV with make_document — that has no styling.\n" +
                    "Rules: there is no internet during layout, so use system fonts only and no " +
                    "external links; embed images as data:image/...;base64. Keep a CV to ONE " +
                    "page — if it does not fit, cut the text rather than the layout.",
                JSONObject()
                    .put("path", str("Path without an extension, for example CV/my-cv-developer"))
                    .put("html", str("The complete HTML document")),
                listOf("path", "html")
            ))
            arr.put(fn(
                "tailor_cv",
                "Prepares a CV FOR ONE SPECIFIC JOB. How to work:\n" +
                    "1) read the advert — page_text for the open page, or open_page for a link;\n" +
                    "2) get the user's own material: this tool returns their saved base CV if " +
                    "there is one, plus the facts in memory;\n" +
                    "3) build the HTML from cv_template so that the wording of the experience and " +
                    "the profile section answer the advert in the advert's own words — but ONLY " +
                    "from the user's real experience. Invent nothing: ask for what is missing " +
                    "instead;\n" +
                    "4) save it with render_html, naming the file after the company.",
                JSONObject().put("note", str("Optional: what to emphasise")),
                emptyList()
            ))
            arr.put(fn(
                "make_document",
                "Creates a finished document — a CV, a letter, a report — in BOTH forms at once: " +
                    ".docx to edit and send, .pdf to print. Both come from the same content, so " +
                    "they look the same.\n" +
                    "Write the content as markup: \"# Name\" is the document title, \"## Section\" " +
                    "a section heading, \"- item\" a list, \"---\" a horizontal rule, and a blank " +
                    "line a gap. For a CV use the sections: contacts, experience, education, " +
                    "skills, languages.",
                JSONObject()
                    .put("path", str("Path without an extension, for example CV/my-cv"))
                    .put("content", str("The document content, as markup"))
                    .put("pdf", JSONObject().put("type", "boolean")
                        .put("description", "Also produce a PDF; yes by default")),
                listOf("path", "content")
            ))
            arr.put(fn(
                "office_read",
                "Reads the text of a Word (.docx), Excel (.xlsx) or PowerPoint (.pptx) document. " +
                    "A spreadsheet comes back as rows with tab-separated columns. " +
                    "The older .doc and .xls are not supported — they have to be re-saved.",
                JSONObject().put("path", str("Path to the document")),
                listOf("path")
            ))
            arr.put(fn(
                "office_fill",
                "Substitutes values into a .docx template and saves a NEW file beside it; the " +
                    "original is left untouched. `values` is an object {what to replace: with " +
                    "what}. The key is text that appears in the document, for example {{name}} " +
                    "or ____. Images, styles, headers and footers are preserved.",
                JSONObject()
                    .put("path", str("Path to the .docx"))
                    .put("values", JSONObject().put("type", "object")
                        .put("description", "{what to replace: with what}")),
                listOf("path", "values")
            ))
            arr.put(fn(
                "office_create",
                "Creates a new document: Word (.docx) from text, or Excel (.xlsx) from a table. " +
                    "For a table, separate rows with line breaks and columns with a tab or a " +
                    "semicolon.",
                JSONObject()
                    .put("path", str("Path of the new file, with a .docx or .xlsx extension"))
                    .put("content", str("The document text, or the table")),
                listOf("path", "content")
            ))
            arr.put(fn(
                "pdf_form_scan",
                "Finds EVERY blank on a printed form — dotted lines, dashes, underscores — and " +
                    "returns them as a numbered list with their labels. Repeated labels are " +
                    "distinguished: \"Nom de naissance\" and \"Nom de naissance#2\".\n" +
                    "Call this FIRST for a form that has no real form fields, then pass the " +
                    "numbers to pdf_autofill: {\"#3\": \"value\", \"#7\": \"another value\"}. " +
                    "That is more reliable than matching on labels.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("page", int_("Page, 1 by default")),
                listOf("path")
            ))
            arr.put(fn(
                "pdf_autofill",
                "FILLS A WHOLE FORM IN ONE CALL — the main tool for a PDF with no form fields.\n" +
                    "Nothing needs to be called first: it reads the page layout itself, finds " +
                    "the field labels, places the values after them, saves a new file and " +
                    "returns the finished page as an image to check.\n" +
                    "`values` is an object {label printed on the form: what to write}. Write the " +
                    "key exactly as it is printed, and USE THE LONGER PHRASE rather than one " +
                    "short word: \"Fait à\" not \"à\", \"Nom de naissance\" not \"Nom\". Short " +
                    "words occur many times on a page and the value lands in the wrong place; " +
                    "keys shorter than three letters are rejected.\n" +
                    "Example: {\"Je soussigné\": \"the user's name\", \"demeurant\": \"their " +
                    "address\"}. When no matching label exists on the form, give the value as " +
                    "\"text@x,y\" with percentages. The reply says which line each value landed " +
                    "on — check it against what you intended.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("values", JSONObject().put("type", "object")
                        .put("description", "{label on the form: value}"))
                    .put("page", int_("Page, 1 by default"))
                    .put("size", int_("Font size, 10 by default")),
                listOf("path", "values")
            ))
            arr.put(fn(
                "pdf_layout",
                "Where every line is printed on the page: the text and its coordinates as " +
                    "percentages. Call this BEFORE pdf_stamp for a document with no form " +
                    "fields — otherwise the coordinates are invented and the text lands on top " +
                    "of the field's own label.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("page", int_("Page number, 1 by default")),
                listOf("path")
            ))
            arr.put(fn(
                "pdf_view",
                "Renders a page of a PDF and sends it as an image in the next message. " +
                    "Use it if you can read images: it shows the real form. Useful for checking " +
                    "a document you have already filled in, too.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("page", int_("Page number, 1 by default")),
                listOf("path")
            ))
            arr.put(fn(
                "pdf_stamp",
                "Stamps text into a document that has no form fields and saves a new file.\n" +
                    "IMPORTANT: pass ALL the pieces of text in `items` in ONE call. Each call " +
                    "starts from the original file again, so two calls produce two files with " +
                    "one piece of text each.\n" +
                    "For each piece, prefer `after` — part of a line already printed on the " +
                    "form, after which it should go; the coordinates are then worked out for " +
                    "you, and the lines come from pdf_layout. The x and y percentages are only " +
                    "needed when the form has no suitable label to anchor to.",
                JSONObject()
                    .put("path", str("Path to the PDF"))
                    .put("items", JSONObject().put("type", "array")
                        .put("description", "Every piece: [{text, after or x and y, page, size, dx, dy}]")
                        .put("items", JSONObject().put("type", "object")))
                    .put("text", str("A single piece of text — when not using items"))
                    .put("after", str("Which printed line to place it after"))
                    .put("page", int_("Page number, 1 by default"))
                    .put("x", int_("Left to right, 0–100"))
                    .put("y", int_("Top to bottom, 0–100"))
                    .put("size", int_("Font size, 11 by default")),
                listOf("path")
            ))
        }
        if (Prefs.aiToolsTasks) {
            arr.put(fn(
                "add_reminder",
                "Creates a task with a reminder — an alarm or a notification will fire. " +
                    "The time now is " + nowStamp() + ". Give the time absolutely as YYYY-MM-DD HH:MM; " +
                    "HH:MM is also accepted (the next such time), as is relative +30m / +2h / +3d.",
                JSONObject()
                    .put("title", str("What needs doing"))
                    .put("when", str("When to remind"))
                    .put("notes", str("Details, optional"))
                    .put("repeat", str("once, daily, weekdays, weekly, monthly"))
                    .put("alarm", JSONObject().put("type", "boolean")
                        .put("description", "true for a loud alarm, false for a quiet notification"))
                    .put("url", str("The page the task is about — the notification opens it")),
                listOf("title", "when")
            ))
            arr.put(fn("list_reminders", "The task list with their numbers. The time now is " + nowStamp() + ".",
                JSONObject().put("days", int_("How many days ahead, 30 by default")), emptyList()))
            arr.put(fn("complete_reminder", "Marks a task done, by its number from list_reminders.",
                JSONObject().put("id", int_("Task number")), listOf("id")))
            arr.put(fn("delete_reminder", "Deletes a task by number. The user confirms by hand.",
                JSONObject().put("id", int_("Task number")), listOf("id")))
        }

        return arr
    }

    fun handles(name: String): Boolean {
        for (i in 0 until specs().length()) {
            if (specs().optJSONObject(i)?.optJSONObject("function")?.optString("name") == name) return true
        }
        return false
    }

    // ---------------- execution ----------------

    /** Blocking; called from the streaming thread. Returns text for the model. */
    fun run(name: String, args: JSONObject, onStatus: (String) -> Unit): String {
        host?.let { AiActionLog.init(it) }
        val out = runInner(name, args, onStatus)
        // v1.7.50: every action the assistant takes is recorded, so "what did
        // it just do?" has an answer instead of a guess
        AiActionLog.add(name, args.toString(), out)
        return out
    }

    private fun runInner(name: String, args: JSONObject, onStatus: (String) -> Unit): String {
        val a = host ?: return "The browser is unavailable."
        return try {
            when (name) {
                "page_text" -> { onStatus(a.getString(R.string.st_page_read)); pageText() }
                "page_extract" -> {
                    onStatus(a.getString(R.string.st_page_extract))
                    pageExtract(
                        args.optString("selector"),
                        args.optString("attribute").ifBlank { null },
                        args.optInt("limit", MAX_ROWS)
                    )
                }
                "page_click" -> {
                    val sel = args.optString("selector")
                    if (!confirm(a, a.getString(R.string.ai_act_click), sel)) "The user refused."
                    else { onStatus(a.getString(R.string.st_page_click)); pageClick(sel) }
                }
                "page_fill" -> {
                    val sel = args.optString("selector")
                    val v = args.optString("value")
                    if (!confirm(a, a.getString(R.string.ai_act_fill), "$sel ← $v")) "The user refused."
                    else { onStatus(a.getString(R.string.st_page_fill)); pageFill(sel, v) }
                }
                "page_scroll" -> { onStatus(a.getString(R.string.st_page_scroll)); pageScroll(args.optString("to")) }
                "page_forms" -> { onStatus(a.getString(R.string.st_page_forms)); pageForms(args.optInt("limit", MAX_ROWS)) }
                "page_screenshot" -> { onStatus(a.getString(R.string.st_page_shot)); pageScreenshot() }

                // v1.7.60: scripts
                // SECURITY (audit A-006, fixed 1.8.5): this is the most powerful
                // tool here and it was the only page tool that never asked.
                // page_click confirms. page_fill confirms. Arbitrary JavaScript
                // on whatever page is open — able to read the DOM of a logged-in
                // session, reach document.cookie and POST it anywhere — did not.
                //
                // It matters because the model's input is not trusted: the
                // assistant reads page text, and a page can carry instructions
                // aimed at the model. Prompt injection then had a direct line to
                // arbitrary script execution on the user's own session.
                "run_js" -> {
                    val code = args.optString("code")
                    if (!confirm(a, a.getString(R.string.ai_act_run_js), code.take(400)))
                        "The user refused."
                    else { onStatus(a.getString(R.string.st_run_js)); runJs(code) }
                }
                "add_userscript" -> {
                    onStatus(a.getString(R.string.st_script_new))
                    addUserscript(a, args.optString("name"), args.optString("code"), args.optString("domains"))
                }
                "list_userscripts" -> listUserscripts(a)
                "toggle_userscript" -> toggleUserscript(a, args.optLong("id", -1), args.optBoolean("enabled", true))
                "delete_userscript" -> deleteUserscript(a, args.optLong("id", -1))

                // v1.7.57: memory across chats
                "remember_fact" -> rememberFact(args.optString("fact"))
                "list_memory" -> listMemory()
                "forget_fact" -> forgetFact(args.optLong("id", -1))

                "open_tab" -> {
                    val url = args.optString("url")
                    onStatus(a.getString(R.string.st_open_fmt, shortHost(url)))
                    openTab(a, url)
                }
                "browser_search" -> {
                    val q = args.optString("query")
                    onStatus(a.getString(R.string.st_search_fmt, q.take(30)))
                    browserSearch(a, q)
                }
                "list_tabs" -> listTabs(a)
                "close_tab" -> {
                    val i = args.optInt("index", -1)
                    if (!confirm(a, a.getString(R.string.ai_act_close_tab), "#$i")) "The user refused."
                    else closeTab(a, i)
                }

                "search_history" -> {
                    onStatus(a.getString(R.string.st_see_history))
                    searchHistory(a, args.optString("query"), args.optInt("limit", 20))
                }
                "search_bookmarks" -> {
                    onStatus(a.getString(R.string.st_see_bookmarks))
                    searchBookmarks(a, args.optString("query"))
                }
                "add_bookmark" -> {
                    val url = args.optString("url")
                    val title = args.optString("title").ifBlank { url }
                    if (!confirm(a, a.getString(R.string.ai_act_bookmark), "$title\n$url")) "The user refused."
                    else addBookmark(a, url, title)
                }
                "list_downloads" -> { onStatus(a.getString(R.string.st_see_downloads)); listDownloads(a, args.optInt("limit", 20)) }
                "find_file" -> {
                    onStatus(a.getString(R.string.st_find_file))
                    findFile(a, args.optString("name"), args.optInt("limit", 20))
                }
                "read_file" -> { onStatus(a.getString(R.string.st_read_file)); readFile(args.optString("path")) }
                "find_everything" -> { onStatus(a.getString(R.string.st_find_all)); findEverything(a, args.optString("query")) }

                "list_ai_models" -> listAiModels()
                "add_ai_model" -> {
                    val n = args.optString("name")
                    val ep = args.optString("endpoint")
                    val mdl = args.optString("model")
                    val key = args.optString("key")
                    val masked = when {
                        key.isBlank() -> "(no key)"
                        key.length > 8 -> key.take(4) + "••••" + key.takeLast(4)
                        else -> "••••"
                    }
                    // A credential is always confirmed — even when the user has
                    // turned confirmations off for ordinary actions.
                    if (!confirmAlways(a, a.getString(R.string.ai_act_add_model),
                            n + "\n" + mdl + "\n" + ep + "\n" + masked)
                    ) "The user refused." else addAiModel(n, ep, mdl, key)
                }
                "use_ai_model" -> useAiModel(args.optString("name"))

                // v1.7.54: workbench
                "write_file" -> {
                    onStatus(a.getString(R.string.st_write_file))
                    writeFile(a, args.optString("path"), args.optString("content"), args.optBoolean("append", false))
                }
                "make_dir" -> { onStatus(a.getString(R.string.st_mkdir)); makeDir(args.optString("path")) }
                "list_dir" -> { onStatus(a.getString(R.string.st_see_dir)); listDir(args.optString("path")) }
                "delete_path" -> { onStatus(a.getString(R.string.st_delete)); deletePath(a, args.optString("path")) }
                "copy_path" -> { onStatus(a.getString(R.string.st_copy)); copyPath(args.optString("from"), args.optString("to"), move = false) }
                "move_path" -> { onStatus(a.getString(R.string.st_move)); copyPath(args.optString("from"), args.optString("to"), move = true) }
                // v1.7.61: PDF
                // v1.7.61: scenarios
                "save_workflow" -> saveWorkflow(a, args.optString("name"), args.optString("prompt"))
                "list_workflows" -> listWorkflows()
                "delete_workflow" -> deleteWorkflow(args.optString("name"))

                "save_template" -> {
                    onStatus(a.getString(R.string.st_tpl_save))
                    saveTemplate(a, args.optString("name"), args.optString("category"),
                        args.optString("kind"), args.optString("html"), args.optString("note"))
                }
                "list_templates" -> listTemplates(args.optString("category"))
                "delete_template" -> deleteTemplate(args.optString("name"))
                "business_profile" -> businessProfile(args.optString("number_kind"))
                "use_template" -> {
                    onStatus(a.getString(R.string.st_tpl_fill))
                    useTemplate(a, args.optString("name"), args.optJSONObject("values"),
                        args.optString("path"), args.optString("number_kind"),
                        args.optBoolean("sign", false))
                }
                "make_cv" -> {
                    onStatus(a.getString(R.string.st_cv_build))
                    makeCv(a, args.optString("path"), args.optJSONObject("data"),
                        args.optString("template"), args.optString("accent"), args.optString("lang"))
                }
                "cv_template" -> cvTemplate(args.optString("name"), args.optString("lang"))
                "tailor_cv" -> { onStatus(a.getString(R.string.st_cv_data)); tailorCv(a, args.optString("note")) }
                "render_html" -> {
                    onStatus(a.getString(R.string.st_render))
                    renderHtml(a, args.optString("path"), args.optString("html"))
                }
                "make_document" -> {
                    onStatus(a.getString(R.string.st_doc_build))
                    makeDocument(a, args.optString("path"), args.optString("content"), args.optBoolean("pdf", true))
                }
                "office_read" -> { onStatus(a.getString(R.string.st_doc_read)); officeRead(a, args.optString("path")) }
                "office_fill" -> {
                    onStatus(a.getString(R.string.st_doc_fill))
                    officeFill(a, args.optString("path"), args.optJSONObject("values"))
                }
                "office_create" -> {
                    onStatus(a.getString(R.string.st_doc_new))
                    officeCreate(a, args.optString("path"), args.optString("content"))
                }

                "pdf_read" -> {
                    onStatus(a.getString(R.string.st_pdf_read))
                    pdfRead(a, args.optString("path"), args.optInt("from_page", 1), args.optInt("to_page", 0))
                }
                "pdf_fields" -> { onStatus(a.getString(R.string.st_pdf_fields)); pdfFields(a, args.optString("path")) }
                "pdf_fill" -> {
                    onStatus(a.getString(R.string.st_pdf_fill))
                    pdfFill(a, args.optString("path"), args.optJSONObject("values"), args.optBoolean("flatten", false))
                }
                "pdf_stamp" -> { onStatus(a.getString(R.string.st_pdf_stamp)); pdfStamp(a, args.optString("path"), args) }
                "pdf_form_scan" -> { onStatus(a.getString(R.string.st_form_scan)); pdfFormScan(a, args.optString("path"), args.optInt("page", 1)) }
                "pdf_autofill" -> {
                    onStatus(a.getString(R.string.st_form_fill))
                    pdfAutofill(a, args.optString("path"), args.optJSONObject("values"),
                        args.optInt("page", 1), args.optInt("size", 10))
                }
                "pdf_layout" -> { onStatus(a.getString(R.string.st_pdf_layout)); pdfLayout(a, args.optString("path"), args.optInt("page", 1)) }
                "pdf_view" -> { onStatus(a.getString(R.string.st_pdf_render)); pdfView(args.optString("path"), args.optInt("page", 1)) }
                "zip_project" -> {
                    onStatus(a.getString(R.string.st_zip))
                    zipProject(a, args.optString("path"), args.optString("name"))
                }

                // v1.7.54: tasks
                "add_reminder" -> {
                    onStatus(a.getString(R.string.st_task_new))
                    addReminder(
                        a, args.optString("title"), args.optString("when"),
                        args.optString("notes"), args.optString("repeat"), args.optString("url"),
                        if (args.has("alarm")) args.optBoolean("alarm", true) else true
                    )
                }
                "list_reminders" -> { onStatus(a.getString(R.string.st_task_list)); listReminders(a, args.optInt("days", 30)) }
                "complete_reminder" -> { onStatus(a.getString(R.string.st_task_done)); completeReminder(a, args.optLong("id", -1)) }
                "delete_reminder" -> { onStatus(a.getString(R.string.st_task_del)); deleteReminder(a, args.optLong("id", -1)) }

                else -> "Unknown tool: $name"
            }
        } catch (t: Throwable) {
            "The tool failed: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    // ---------------- page automation ----------------

    private fun pageText(): String =
        evalJs("(function(){return document.body?document.body.innerText:''})()")
            ?.take(MAX_TEXT)?.ifBlank { "The page is empty." } ?: "The page is unavailable."

    private fun pageExtract(selector: String, attribute: String?, limit: Int): String {
        if (selector.isBlank()) return "No selector given."
        val n = limit.coerceIn(1, 200)
        val attrExpr = if (attribute.isNullOrBlank()) "(e.innerText||'').trim()"
        else "(e.getAttribute(${q(attribute)})||'')"
        val js = """
            (function(){
              try{
                var els = document.querySelectorAll(${q(selector)});
                var out = [];
                for (var i=0; i<els.length && out.length<$n; i++){
                  var e = els[i];
                  var v = $attrExpr;
                  if (v) out.push(v);
                }
                return JSON.stringify({count: els.length, items: out});
              }catch(err){ return JSON.stringify({error: String(err)}); }
            })()
        """.trimIndent()
        val raw = evalJs(js) ?: return "The page is unavailable."
        return try {
            val o = JSONObject(raw)
            o.optString("error").takeIf { it.isNotBlank() }?.let { return "Selector error: $it" }
            val items = o.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) return "Nothing matched the selector \"$selector\"."
            val sb = StringBuilder("Elements found: ").append(o.optInt("count"))
                .append(", returned ").append(items.length()).append(":\n")
            for (i in 0 until items.length()) sb.append(i + 1).append(". ").append(items.optString(i)).append('\n')
            sb.toString().take(MAX_TEXT)
        } catch (_: Throwable) { raw.take(MAX_TEXT) }
    }

    private fun pageClick(selector: String): String {
        val js = """
            (function(){
              var e = document.querySelector(${q(selector)});
              if(!e) return 'notfound';
              e.scrollIntoView({block:'center'});
              e.click();
              return 'ok';
            })()
        """.trimIndent()
        return when (evalJs(js)) {
            "ok" -> "Clicked. Wait a second and re-read the page — the content may have changed."
            "notfound" -> "No element matched the selector \"$selector\"."
            else -> "Could not click."
        }
    }

    private fun pageFill(selector: String, value: String): String {
        val js = """
            (function(){
              var e = document.querySelector(${q(selector)});
              if(!e) return 'notfound';
              e.focus();
              e.value = ${q(value)};
              e.dispatchEvent(new Event('input', {bubbles:true}));
              e.dispatchEvent(new Event('change', {bubbles:true}));
              return 'ok';
            })()
        """.trimIndent()
        return when (evalJs(js)) {
            "ok" -> "Field filled in."
            "notfound" -> "No field matched the selector \"$selector\"."
            else -> "Could not fill the field."
        }
    }

    private fun pageScroll(to: String): String {
        val js = when {
            to.equals("bottom", true) -> "window.scrollTo(0, document.body.scrollHeight); 'ok'"
            to.equals("top", true) -> "window.scrollTo(0, 0); 'ok'"
            else -> {
                val px = to.toIntOrNull() ?: 800
                "window.scrollBy(0, $px); 'ok'"
            }
        }
        evalJs("(function(){ $js })()")
        return "Scrolled. Lazy loading may take a second."
    }


    /**
     * v1.7.55: describe the page's forms so the model can fill them on purpose.
     *
     * The selector is built here, in the page, rather than left to the model:
     * an id when there is one, then a name, then a positional
     * `:nth-of-type` path. Guessing a selector from a screenshot of a language
     * you do not read is exactly how the wrong box gets filled.
     *
     * Passwords are listed but their current value never leaves the page — a
     * form description must not become a way to read what is already typed.
     */
    private fun pageForms(limit: Int): String {
        val n = limit.coerceIn(1, 120)
        val js = """
            (function(){
              try{
                function cssPath(el){
                  if (el.id) return '#' + CSS.escape(el.id);
                  if (el.name) return el.tagName.toLowerCase() + '[name="' + el.name.replace(/"/g,'\\"') + '"]';
                  var parts = [];
                  var cur = el;
                  while (cur && cur.nodeType === 1 && parts.length < 6) {
                    var tag = cur.tagName.toLowerCase();
                    if (cur.id) { parts.unshift('#' + CSS.escape(cur.id)); break; }
                    var i = 1, sib = cur;
                    while ((sib = sib.previousElementSibling)) if (sib.tagName === cur.tagName) i++;
                    parts.unshift(tag + ':nth-of-type(' + i + ')');
                    cur = cur.parentElement;
                  }
                  return parts.join(' > ');
                }
                function labelOf(el){
                  if (el.id) {
                    var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
                    if (l && l.innerText.trim()) return l.innerText.trim();
                  }
                  var p = el.closest('label');
                  if (p && p.innerText.trim()) return p.innerText.trim();
                  var aria = el.getAttribute('aria-label');
                  if (aria) return aria.trim();
                  if (el.placeholder) return el.placeholder.trim();
                  var prev = el.previousElementSibling;
                  if (prev && prev.innerText && prev.innerText.trim().length < 80) return prev.innerText.trim();
                  return '';
                }
                function visible(el){
                  if (el.type === 'hidden') return false;
                  var r = el.getBoundingClientRect();
                  return r.width > 0 && r.height > 0;
                }
                var out = [], nodes = document.querySelectorAll('input, textarea, select');
                for (var i = 0; i < nodes.length && out.length < $n; i++) {
                  var el = nodes[i];
                  if (!visible(el)) continue;
                  var type = (el.tagName.toLowerCase() === 'input' ? (el.type || 'text') : el.tagName.toLowerCase());
                  var item = {
                    selector: cssPath(el),
                    type: type,
                    label: labelOf(el).substring(0, 120),
                    required: !!el.required,
                    filled: type === 'password' ? (el.value ? true : false) : undefined,
                    value: type === 'password' ? undefined : String(el.value || '').substring(0, 80)
                  };
                  if (el.tagName.toLowerCase() === 'select') {
                    item.options = Array.prototype.slice.call(el.options, 0, 25)
                      .map(function(o){ return (o.value || '') + '|' + (o.text || '').trim(); });
                  }
                  out.push(item);
                }
                var buttons = [];
                var bnodes = document.querySelectorAll(
                  'button, input[type=submit], input[type=button], [role=button]');
                for (var j = 0; j < bnodes.length && buttons.length < 12; j++) {
                  var b = bnodes[j];
                  if (!visible(b)) continue;
                  var t = (b.innerText || b.value || b.getAttribute('aria-label') || '').trim();
                  if (!t) continue;
                  buttons.push({ selector: cssPath(b), text: t.substring(0, 60) });
                }
                return JSON.stringify({ fields: out, buttons: buttons, url: location.href });
              } catch(err) { return JSON.stringify({ error: String(err) }); }
            })()
        """.trimIndent()

        val raw = evalJs(js) ?: return "The page is unavailable."
        return try {
            val o = JSONObject(raw)
            o.optString("error").takeIf { it.isNotBlank() }?.let { return "Could not parse the form: $it" }
            val fields = o.optJSONArray("fields") ?: JSONArray()
            val buttons = o.optJSONArray("buttons") ?: JSONArray()
            if (fields.length() == 0 && buttons.length() == 0) return "No form fields are visible on the page."
            val sb = StringBuilder("Page: ").append(o.optString("url")).append('\n')
            if (fields.length() > 0) sb.append("\nFields (selector → what it is):\n")
            for (i in 0 until fields.length()) {
                val f = fields.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(f.optString("selector")).append('\n')
                sb.append("   type: ").append(f.optString("type"))
                if (f.optBoolean("required")) sb.append(", required")
                f.optString("label").takeIf { it.isNotBlank() }?.let { sb.append("\n   label: ").append(it) }
                f.optString("value").takeIf { it.isNotBlank() }?.let { sb.append("\n   current: ").append(it) }
                f.optJSONArray("options")?.let { opts ->
                    sb.append("\n   options: ")
                    for (k in 0 until opts.length()) {
                        if (k > 0) sb.append("; ")
                        sb.append(opts.optString(k))
                    }
                }
                sb.append('\n')
            }
            if (buttons.length() > 0) {
                sb.append("\nButtons:\n")
                for (i in 0 until buttons.length()) {
                    val b = buttons.optJSONObject(i) ?: continue
                    sb.append("• ").append(b.optString("text")).append("  →  ")
                        .append(b.optString("selector")).append('\n')
                }
            }
            sb.append("\nFill these in with page_fill using these selectors, then submit with page_click. " +
                "Never invent passwords or personal details — ask the user.")
            sb.toString().take(MAX_TEXT)
        } catch (_: Throwable) { raw.take(MAX_TEXT) }
    }
    // ---------------- v1.7.60: userscripts the assistant writes ----------------

    /**
     * Run JS once on the current page.
     *
     * Not confirmed, deliberately: this is the same power page_extract and
     * page_click already have, expressed directly, and asking for every
     * calculation would make the tool useless. It is still gated by its own
     * switch, and it cannot touch anything outside the page.
     */
    private fun runJs(code: String): String {
        if (code.isBlank()) return "Empty code."
        // wrapped so the last expression is the result and a throw comes back
        // as text instead of vanishing
        val js = """
            (function(){
              try { return String((function(){ $code })()); }
              catch(e) { return 'ERROR: ' + String(e); }
            })()
        """.trimIndent()
        val out = evalJs(js, timeoutSec = 20) ?: return "The page is unavailable."
        return when {
            out.isBlank() -> "Done (no return value)."
            out.startsWith("ERROR:") -> out
            else -> "Result: " + out.take(MAX_TEXT)
        }
    }

    /**
     * Create a permanent userscript.
     *
     * Always confirmed, even when the trust switch is off, and the dialog shows
     * the code: a script runs on every matching page from now on, without the
     * user present. That is a different kind of permission from one click.
     */
    private fun addUserscript(a: MainActivity, name: String, code: String, domains: String): String {
        if (code.isBlank()) return "Empty code."
        val where = domains.trim().ifBlank { "*" }
        val title = name.trim().ifBlank { "script" }
        // v1.7.61: the same review as a file write — code is code, and this one
        // keeps running on every matching page afterwards, so it deserves the
        // diff at least as much as a throwaway .txt did. Never covered by a
        // blanket "apply all" given for a batch of project files.
        val choice = AiWriteReview.review(
            a,
            title = title,
            subtitle = a.getString(R.string.rv_script_on, where),
            before = null,
            after = code,
            kindRes = R.string.rv_script_new,
            blanketApplies = false
        )
        if (choice != AiWriteReview.Choice.APPLY) return "The user refused."
        return runBlocking {
            try {
                val dao = AppDb.get(a.applicationContext).scriptDao()
                val id = dao.insert(
                    com.zephyr.browser.db.ScriptEntity(
                        name = title, code = code, enabled = true, domains = where
                    )
                )
                com.zephyr.browser.userscripts.ScriptInjector.refresh()
                AiActionLog.offerUndo(JSONObject().put("kind", AiActionLog.Kind.SCRIPT).put("id", id))
                "Script #" + id + " \"" + title + "\" created and enabled for: " + where +
                    ".\nIt will run by itself whenever a matching page opens. " +
                    "Reload the page to see it work now."
            } catch (t: Throwable) {
                "Could not create the script: " + (t.localizedMessage ?: t.javaClass.simpleName)
            }
        }
    }

    private fun listUserscripts(a: MainActivity): String = runBlocking {
        try {
            val rows = AppDb.get(a.applicationContext).scriptDao().allOnce()
            if (rows.isEmpty()) "No user scripts."
            else rows.joinToString("\n") { s ->
                "#" + s.id + " " + (if (s.enabled) "●" else "○") + " " + s.name +
                    "\n  where: " + s.domains + ", lines: " + s.code.count { it == '\n' }.plus(1)
            }
        } catch (t: Throwable) { "Scripts unavailable: " + t.localizedMessage }
    }

    private fun toggleUserscript(a: MainActivity, id: Long, on: Boolean): String = runBlocking {
        if (id < 0) return@runBlocking "No script number given."
        try {
            val dao = AppDb.get(a.applicationContext).scriptDao()
            val s = dao.byId(id) ?: return@runBlocking "There is no script #$id."
            dao.setEnabled(id, on)
            com.zephyr.browser.userscripts.ScriptInjector.refresh()
            "Script \"" + s.name + "\" " + (if (on) "enabled." else "disabled.")
        } catch (t: Throwable) { "Did not work: " + t.localizedMessage }
    }

    private fun deleteUserscript(a: MainActivity, id: Long): String = runBlocking {
        if (id < 0) return@runBlocking "No script number given."
        try {
            val dao = AppDb.get(a.applicationContext).scriptDao()
            val s = dao.byId(id) ?: return@runBlocking "There is no script #$id."
            // deleting a script shows what is being removed, in the same window
            val choice = AiWriteReview.review(
                a,
                title = s.name,
                subtitle = a.getString(R.string.rv_script_on, s.domains),
                before = s.code,
                after = "",
                kindRes = R.string.rv_script_del,
                blanketApplies = false
            )
            if (choice != AiWriteReview.Choice.APPLY) return@runBlocking "The user refused."
            dao.delete(s)
            com.zephyr.browser.userscripts.ScriptInjector.refresh()
            "Script \"" + s.name + "\" deleted."
        } catch (t: Throwable) { "Did not work: " + t.localizedMessage }
    }

    // ---------------- browser ----------------

    /**
     * v1.7.46 FIX (user: "I say open the link, it answers that it did, and
     * nothing actually opens"): the page DID open. It opened behind the
     * assistant window, which covers the whole screen, so nothing was visible
     * and the answer looked like a lie. Opening a page now collapses the chat
     * into the floating bubble, so the user actually sees what happened.
     */
    private fun openTab(a: MainActivity, url: String): String {
        if (!url.startsWith("http", true)) return "A full address starting with http is required."
        onMain(a) {
            it.tabs.newTab(url, it.tabs.current?.incognito ?: false)
            it.navigate(url)
            AiAssistantSheet.minimizeForAction(it)
        }
        return "Tab opened: $url. The assistant window is minimised and the page is visible to the user."
    }

    private fun browserSearch(a: MainActivity, query: String): String {
        if (query.isBlank()) return "Empty query."
        val url = com.zephyr.browser.SearchEngines.searchUrl(Prefs.engineId, query)
        onMain(a) {
            it.tabs.newTab(url, it.tabs.current?.incognito ?: false)
            it.navigate(url)
            AiAssistantSheet.minimizeForAction(it)
        }
        return "Search started in the browser: $query. The assistant window is minimised."
    }

    private fun listTabs(a: MainActivity): String {
        val list = onMain(a) { act ->
            act.tabs.tabs.mapIndexed { i, t ->
                "$i. " + (t.title.ifBlank { "(no title)" }) + " — " + t.pageUrlSnapshot
            }
        } ?: return "Could not read the tabs."
        return if (list.isEmpty()) "No open tabs." else list.joinToString("\n")
    }

    private fun closeTab(a: MainActivity, index: Int): String {
        val ok = onMain(a) { act ->
            val t = act.tabs.tabs.getOrNull(index) ?: return@onMain false
            act.tabs.closeTab(t, selectReplacement = true)
            true
        } ?: false
        return if (ok) "Tab $index closed." else "There is no tab with index $index."
    }

    // ---------------- user data ----------------

    private fun searchHistory(ctx: Context, query: String, limit: Int): String = runBlocking {
        try {
            val dao = AppDb.get(ctx.applicationContext).historyDao()
            val rows = if (query.isBlank()) dao.recentSince(0, limit.coerceIn(1, MAX_ROWS))
            else dao.search(query).take(limit.coerceIn(1, MAX_ROWS))
            if (rows.isEmpty()) "Nothing in the history matches \"$query\"."
            else rows.joinToString("\n") {
                "• " + it.title.ifBlank { it.url } + "\n  " + it.url + "\n  " + date(it.visitedAt)
            }
        } catch (t: Throwable) { "History unavailable: " + t.localizedMessage }
    }

    private fun searchBookmarks(ctx: Context, query: String): String = runBlocking {
        try {
            val all = AppDb.get(ctx.applicationContext).bookmarkDao().allOnce()
            val rows = if (query.isBlank()) all else all.filter {
                it.title.contains(query, true) || it.url.contains(query, true)
            }
            if (rows.isEmpty()) "Nothing in the bookmarks matches \"$query\"."
            else rows.take(MAX_ROWS).joinToString("\n") { "• " + it.title + "\n  " + it.url }
        } catch (t: Throwable) { "Bookmarks unavailable: " + t.localizedMessage }
    }

    private fun addBookmark(ctx: Context, url: String, title: String): String = runBlocking {
        try {
            AppDb.get(ctx.applicationContext).bookmarkDao()
                .insert(BookmarkEntity(title = title, url = url))
            AiActionLog.offerUndo(JSONObject().put("kind", AiActionLog.Kind.BOOKMARK).put("url", url))
            "Bookmark added: $title"
        } catch (t: Throwable) { "Could not add the bookmark: " + t.localizedMessage }
    }

    private fun listDownloads(ctx: Context, limit: Int): String = runBlocking {
        try {
            val rows = AppDb.get(ctx.applicationContext).downloadDao().allOnce()
                .take(limit.coerceIn(1, MAX_ROWS))
            if (rows.isEmpty()) "No downloads."
            else rows.joinToString("\n") {
                "• " + it.fileName + "  (" + human(it.size) + ")\n  " + it.path
            }
        } catch (t: Throwable) { "Downloads unavailable: " + t.localizedMessage }
    }

    private fun findFile(ctx: Context, name: String, limit: Int): String {
        if (name.isBlank()) return "No file name given."
        return try {
            val out = ArrayList<String>()
            val uri = android.provider.MediaStore.Files.getContentUri("external")
            val cols = arrayOf(
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.SIZE,
                android.provider.MediaStore.Files.FileColumns.DATA
            )
            ctx.contentResolver.query(
                uri, cols,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c ->
                while (c.moveToNext() && out.size < limit.coerceIn(1, MAX_ROWS)) {
                    val n = c.getString(0) ?: continue
                    val s = c.getLong(1)
                    val p = c.getString(2) ?: ""
                    out.add("• $n  (" + human(s) + ")\n  $p")
                }
            }
            if (out.isEmpty()) "No files with \"$name\" in the name." else out.joinToString("\n")
        } catch (t: Throwable) { "File search unavailable: " + t.localizedMessage }
    }

    private fun readFile(path: String): String = try {
        val f = java.io.File(path)
        when {
            !f.exists() -> "No such file: $path"
            !f.canRead() -> "File is not readable: $path"
            f.length() > 4L * 1024 * 1024 -> "File is too large (" + human(f.length()) + ")."
            else -> {
                val bytes = f.readBytes()
                val s = String(bytes, Charsets.UTF_8)
                val ctrl = s.take(2000).count { it.code < 9 || (it.code in 14..31) }
                if (ctrl > 40) "Not a text file (" + human(f.length()) + ")."
                else s.take(MAX_TEXT)
            }
        }
    } catch (t: Throwable) { "Could not read: " + t.localizedMessage }

    // ---------------- v1.7.54: workbench (writing code on the phone) ----------------

    /**
     * Write a file the model composed.
     *
     * Two confirmations, not one: an ordinary write inside the workspace goes
     * through the usual [confirm] (which the trust switch can silence), while a
     * write that lands OUTSIDE the workspace always asks — that is the case
     * where a wrong path overwrites something the user cares about.
     */
    private fun writeFile(a: MainActivity, path: String, content: String, append: Boolean): String {
        if (content.isEmpty() && !append) return "Empty content — nothing to write."
        return when (val t = AiWorkspace.resolve(path)) {
            is AiWorkspace.Target.Refused -> t.reason
            is AiWorkspace.Target.Ok -> {
                if (t.file.isDirectory) return "That path is a folder, not a file: " + t.file.absolutePath
                // v1.7.55: the review sheet replaces the "here are 600 characters,
                // press OK" alert — and its "apply all" makes a hundred-file
                // project one decision instead of a hundred.
                //
                // A write LANDING OUTSIDE the workspace is the exception: it is
                // never covered by a blanket yes, because each one can overwrite
                // something the user cares about for a different reason.
                if (t.outside) {
                    val body = t.file.absolutePath + "\n\n" +
                        content.take(600) + (if (content.length > 600) "\n… (" + content.length + " characters)" else "")
                    val verb = when {
                        append && t.file.exists() -> a.getString(R.string.ai_act_append)
                        t.file.exists() -> a.getString(R.string.ai_act_overwrite)
                        else -> a.getString(R.string.ai_act_write)
                    }
                    if (!confirmAlways(a, verb, body)) return "The user refused."
                } else {
                    when (AiWriteReview.ask(a, t.file, content, append)) {
                        AiWriteReview.Choice.REJECT -> return "The user rejected this file."
                        AiWriteReview.Choice.REJECT_ALL ->
                            return "The user rejected writing files. Do not offer any more files in this answer."
                        else -> {}
                    }
                }
                try {
                    // captured BEFORE the write, so the answer can be undone
                    val before = try { if (t.file.isFile) t.file.readText() else null }
                    catch (_: Throwable) { null }
                    // v1.7.60: a copy of the old bytes, so the log can offer a
                    // real "undo" days later — not only while the app lives
                    val backup = if (before != null) AiActionLog.stashFile(t.file.absolutePath) else null
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", t.file.absolutePath)
                            .put("backup", backup ?: "")
                    )
                    val diff = AiWriteReview.diff(before, if (append && before != null) before + content else content)
                    val w = AiWorkspace.write(t.file, content, append)
                    AiWorkspace.index(a, w.file)
                    AiWriteReview.record(w.file.absolutePath, before, diff.added, diff.removed)
                    (if (w.existed && !append) "File overwritten: " else "File saved: ") +
                        w.file.absolutePath + " (" + AiWorkspace.human(w.bytes.toLong()) + ")." +
                        "\nThis is saved text — it cannot be run from inside the browser."
                } catch (t2: Throwable) {
                    "Could not write: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
                }
            }
        }
    }


    /**
     * v1.7.58: copy or move inside the workspace.
     *
     * Their absence was the real cause behind the assistant's "I can only zip a
     * folder" workaround: with no copy tool, gathering scattered files meant
     * read_file + write_file per file, which burned a tool round each and ran
     * the answer out of steps before it reached the zip.
     *
     * Both ends are resolved through the normal fence, so neither can reach
     * outside the workspace without the user's explicit switch.
     */
    private fun copyPath(fromRaw: String, toRaw: String, move: Boolean): String {
        val src = AiWorkspace.resolve(fromRaw)
        if (src is AiWorkspace.Target.Refused) return src.reason
        val dst = AiWorkspace.resolve(toRaw)
        if (dst is AiWorkspace.Target.Refused) return dst.reason
        val from = (src as AiWorkspace.Target.Ok).file
        val to = (dst as AiWorkspace.Target.Ok).file
        if (!from.exists()) return "No such file or folder: " + from.absolutePath
        if (from.absolutePath == to.absolutePath) return "Source and destination are the same."
        // moving a folder into itself would recurse until the disk fills
        if (from.isDirectory && to.absolutePath.startsWith(from.absolutePath + "/"))
            return "A folder cannot be placed inside itself."
        return try {
            if (move) {
                to.parentFile?.mkdirs()
                if (from.renameTo(to)) "Moved: " + to.absolutePath
                else {
                    // a rename across volumes fails; copy then remove
                    val n = AiWorkspace.copy(from, to)
                    AiWorkspace.delete(from)
                    "Files moved: $n → " + to.absolutePath
                }
            } else {
                val n = AiWorkspace.copy(from, to)
                "Files copied: $n → " + to.absolutePath
            }
        } catch (t: Throwable) {
            "Failed: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun makeDir(path: String): String = when (val t = AiWorkspace.resolve(path)) {
        is AiWorkspace.Target.Refused -> t.reason
        is AiWorkspace.Target.Ok ->
            if (AiWorkspace.mkdir(t.file)) "Folder ready: " + t.file.absolutePath
            else "Could not create the folder: " + t.file.absolutePath
    }

    private fun listDir(path: String): String {
        val target = if (path.isBlank()) AiWorkspace.Target.Ok(AiWorkspace.root(), false)
        else AiWorkspace.resolve(path)
        return when (target) {
            is AiWorkspace.Target.Refused -> target.reason
            is AiWorkspace.Target.Ok -> {
                val f = target.file
                if (!f.exists()) return "No such folder: " + f.absolutePath
                if (!f.isDirectory) return "That is a file, not a folder: " + f.absolutePath
                val l = AiWorkspace.list(f)
                if (l.entries.isEmpty()) return "The folder is empty: " + f.absolutePath
                val sb = StringBuilder(f.absolutePath).append('\n')
                for ((name, size) in l.entries) {
                    sb.append(if (size < 0) "📁 " else "📄 ").append(name)
                    if (size >= 0) sb.append("  (").append(AiWorkspace.human(size)).append(')')
                    sb.append('\n')
                }
                if (l.truncated) sb.append("… list truncated")
                sb.toString()
            }
        }
    }

    private fun deletePath(a: MainActivity, path: String): String = when (val t = AiWorkspace.resolve(path)) {
        is AiWorkspace.Target.Refused -> t.reason
        is AiWorkspace.Target.Ok -> {
            if (!t.file.exists()) "Nothing to delete: " + t.file.absolutePath
            else if (t.file.absolutePath == AiWorkspace.rootPath()) "The workspace folder itself cannot be deleted."
            // deletion is irreversible and never worth trusting to a switch
            else if (!confirmAlways(a, a.getString(R.string.ai_act_delete), t.file.absolutePath))
                "The user refused."
            else {
                val n = AiWorkspace.delete(t.file)
                if (n > 0) "Items deleted: $n" else "Could not delete: " + t.file.absolutePath
            }
        }
    }

    /**
     * v1.7.58: one path, or several separated by commas.
     *
     * The single-path form drove the assistant to invent a workaround —
     * "create a folder, copy everything in, then I can zip it" — which cost
     * three extra steps for something the archiver can just do.
     */
    private fun zipProject(a: MainActivity, path: String, name: String): String {
        val parts = path.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Nothing specified to pack."

        val files = ArrayList<java.io.File>()
        for (p in parts) {
            when (val t = AiWorkspace.resolve(p)) {
                is AiWorkspace.Target.Refused -> return t.reason
                is AiWorkspace.Target.Ok -> {
                    if (!t.file.exists()) return "No such file or folder: " + t.file.absolutePath
                    files.add(t.file)
                }
            }
        }
        return try {
            val z = if (files.size == 1) AiWorkspace.zip(files[0], name.ifBlank { null })
            else AiWorkspace.zipMany(files, name.ifBlank { null })
            AiWorkspace.index(a, z.file)
            "Archive created: " + z.file.absolutePath +
                "\nFiles inside: " + z.entries + ", size " + AiWorkspace.human(z.bytes)
        } catch (t2: Throwable) {
            "Could not pack: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
        }
    }


    /**
     * v1.7.57: let a vision model SEE the page, not just read its text.
     *
     * page_text loses everything that is not a string — layout, charts, a
     * captcha, a screenshot someone posted. This renders the visible viewport
     * and hands it over as an image.
     *
     * A tool result is text, so the picture cannot travel in it. Instead it is
     * parked in [pendingImage]; AiClient picks it up after the call and appends
     * it as a real image message, which is the only shape providers accept.
     * Non-vision models simply never call this tool.
     */
    @Volatile
    var pendingImage: String? = null

    private const val SHOT_MAX_W = 1024

    private fun pageScreenshot(): String {
        val a = host ?: return "The browser is unavailable."
        val data = onMain(a) { act ->
            try {
                val wv = act.tabs.current?.webView ?: return@onMain null
                val w = wv.width
                val h = wv.height
                if (w <= 0 || h <= 0) return@onMain null
                val full = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888
                )
                wv.draw(android.graphics.Canvas(full))
                // downscale before encoding: a phone screenshot at full density
                // is megabytes of base64, and no model needs that resolution
                val scaled = if (w > SHOT_MAX_W) {
                    val nh = (h.toLong() * SHOT_MAX_W / w).toInt().coerceAtLeast(1)
                    android.graphics.Bitmap.createScaledBitmap(full, SHOT_MAX_W, nh, true)
                } else full
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                if (scaled !== full) scaled.recycle()
                full.recycle()
                "data:image/jpeg;base64," +
                    android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (_: Throwable) { null }
        }
        if (data.isNullOrBlank()) return "Could not take the shot — the page has not rendered."
        pendingImage = data
        return "A shot of the visible part of the page was taken and attached to the next " +
            "message. Describe what is on it, or use it to answer."
    }

    // ---------------- v1.7.57: memory across chats ----------------

    /**
     * Saving a fact is not confirmed the way an action is: it changes nothing
     * outside the app and everything here is listable and deletable from
     * settings. Instead the SAVED TEXT is echoed back, so a wrong fact shows up
     * in the answer the user is already reading rather than silently steering
     * every conversation from then on.
     */
    private fun rememberFact(fact: String): String {
        val saved = AiMemory.add(fact)
            ?: return "That fact already exists, or is empty — nothing was added."
        AiMemory.all().lastOrNull { it.text == saved }?.let { f ->
            AiActionLog.offerUndo(JSONObject().put("kind", AiActionLog.Kind.MEMORY).put("id", f.id))
        }
        return "Remembered: \"" + saved + "\". This outlives the chat. " +
            "The user can view and delete it in the AI settings."
    }

    private fun listMemory(): String {
        val facts = AiMemory.all()
        if (facts.isEmpty()) return "Nothing remembered yet."
        return facts.joinToString("\n") { "#" + it.id + " " + it.text }
    }

    private fun forgetFact(id: Long): String {
        if (id < 0) return "No fact number given."
        return if (AiMemory.remove(id)) "Forgot fact #$id." else "There is no fact #$id."
    }

    /**
     * v1.7.61 "find everything about this".
     *
     * Every one of these stores was already searchable on its own, and that was
     * the problem: answering "what do I have about Paris" meant four tool calls
     * and four rounds, so the model usually settled for one and missed the rest.
     * One call, one round, everything the user owns.
     *
     * Each section is capped and individually guarded — a locked database or a
     * denied storage permission must shrink the answer, never fail it.
     */
    private fun findEverything(a: MainActivity, query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "Nothing given to search for."
        val app = a.applicationContext
        val sb = StringBuilder("Found for \"").append(q).append("\":\n")
        var any = false

        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            any = true
            sb.append('\n').append(title).append(":\n")
            for (l in lines) sb.append("• ").append(l).append('\n')
        }

        try {
            val rows = runBlocking { AppDb.get(app).historyDao().search(q) }
                .take(6).map { it.title.ifBlank { it.url } + "\n  " + it.url }
            section("History", rows)
        } catch (_: Throwable) {}

        try {
            val rows = runBlocking { AppDb.get(app).bookmarkDao().allOnce() }
                .filter { it.title.contains(q, true) || it.url.contains(q, true) }
                .take(6).map { it.title + "\n  " + it.url }
            section("Bookmarks", rows)
        } catch (_: Throwable) {}

        try {
            val rows = runBlocking { AppDb.get(app).downloadDao().allOnce() }
                .filter { it.fileName.contains(q, true) }
                .take(6).map { it.fileName + "\n  " + it.path }
            section("Downloads", rows)
        } catch (_: Throwable) {}

        try {
            val found = findFile(a, q, 6)
            if (!found.startsWith("No files with") && !found.startsWith("File search unavailable")) {
                any = true
                sb.append("\nFiles on the phone:\n").append(found).append('\n')
            }
        } catch (_: Throwable) {}

        try {
            val rows = AiMemory.all().filter { it.text.contains(q, true) }.take(6).map { it.text }
            section("What is remembered about you", rows)
        } catch (_: Throwable) {}

        try {
            val rows = runBlocking { AppDb.get(app).reminderDao().allOnce() }
                .filter { it.title.contains(q, true) || it.notes.contains(q, true) }
                .take(6).map { it.title + " — " + Reminders.whenText(a, it.dueAt) }
            section("Tasks and reminders", rows)
        } catch (_: Throwable) {}

        try {
            val rows = AiWorkflows.all()
                .filter { it.name.contains(q, true) || it.prompt.contains(q, true) }
                .take(4).map { "/" + it.name }
            section("Workflows", rows)
        } catch (_: Throwable) {}

        try {
            val tabs = onMain(a) { act ->
                act.tabs.tabs.filter {
                    it.title.contains(q, true) || it.pageUrlSnapshot.contains(q, true)
                }.take(6).map { it.title.ifBlank { it.pageUrlSnapshot } + "\n  " + it.pageUrlSnapshot }
            } ?: emptyList()
            section("Open tabs", tabs)
        } catch (_: Throwable) {}

        return if (!any) "Nothing found for \"$q\" in history, bookmarks, files, " +
            "tasks or memory." else sb.toString().take(MAX_TEXT)
    }

    // ---------------- v1.7.61: scenarios ----------------

    private fun saveWorkflow(a: MainActivity, name: String, prompt: String): String {
        if (name.isBlank() || prompt.isBlank()) return "Both a name and an instruction are required."
        val existing = AiWorkflows.byName(name)
        // replacing an existing command changes what a familiar word does, so
        // the user sees the whole instruction before it takes effect
        val choice = AiWriteReview.review(
            a,
            title = "/" + name.trim().removePrefix("/"),
            subtitle = a.getString(R.string.wf_review_sub),
            before = existing?.prompt,
            after = prompt,
            kindRes = if (existing == null) R.string.wf_new else R.string.wf_replace,
            blanketApplies = false
        )
        if (choice != AiWriteReview.Choice.APPLY) return "The user refused."
        val f = AiWorkflows.put(name, prompt) ?: return "Could not save the workflow."
        AiActionLog.offerUndo(
            JSONObject().put("kind", AiActionLog.Kind.WORKFLOW).put("id", f.id)
        )
        return "Workflow \"" + f.name + "\" saved. The user runs it from Workflows, " +
            "or schedules it in Tasks."
    }

    private fun listWorkflows(): String {
        val list = AiWorkflows.all()
        if (list.isEmpty()) return "No workflows yet."
        return list.joinToString("\n") { "/" + it.name + "\n  " + it.prompt.take(160) }
    }

    private fun deleteWorkflow(name: String): String {
        val f = AiWorkflows.byName(name) ?: return "There is no workflow \"$name\"."
        return if (AiWorkflows.remove(f.id)) "Workflow \"" + f.name + "\" deleted."
        else "Could not delete."
    }
    // ---------------- v1.7.70: templates ----------------

    private fun saveTemplate(
        a: MainActivity, name: String, category: String, kind: String, html: String, note: String
    ): String {
        if (name.isBlank() || html.isBlank()) return "A template name and its content are required."
        val fields = AiTemplates.placeholdersOf(html)
        val existing = AiTemplates.byName(name)
        val choice = AiWriteReview.review(
            a,
            title = name.trim(),
            subtitle = a.getString(R.string.tpl_review_sub, category.ifBlank { AiTemplates.CATEGORIES[0] }),
            before = existing?.let { try { it.file.readText() } catch (_: Throwable) { null } },
            after = html,
            kindRes = if (existing == null) R.string.tpl_new else R.string.tpl_replace,
            blanketApplies = false
        )
        if (choice != AiWriteReview.Choice.APPLY) return "The user refused."

        val t = AiTemplates.put(name, category, kind, html, fields, note)
            ?: return "Could not save the template."
        AiActionLog.offerUndo(
            JSONObject().put("kind", AiActionLog.Kind.TEMPLATE).put("id", t.id)
        )
        return buildString {
            append("Template \"").append(t.name).append("\" saved in the folder \"").append(t.category).append("\".")
            if (t.fields.isNotEmpty()) {
                append("\nFields: ").append(t.fields.joinToString(", "))
            }
            append("\nRun it with use_template, or from the Templates screen in the menu.")
        }
    }

    private fun listTemplates(category: String): String {
        val all = AiTemplates.all().filter { category.isBlank() || it.category.equals(category, true) }
        if (all.isEmpty()) return "No templates yet. Create one with save_template."
        return buildString {
            var folder = ""
            for (t in all) {
                if (t.category != folder) { folder = t.category; append("\n[").append(folder).append("]\n") }
                append("• ").append(t.name)
                append(if (t.isApp) "  (mini-application)" else "  (document)")
                if (t.note.isNotBlank()) append("\n  ").append(t.note)
                if (t.fields.isNotEmpty()) append("\n  fields: ").append(t.fields.joinToString(", "))
                append('\n')
            }
        }
    }

    private fun deleteTemplate(name: String): String {
        val t = AiTemplates.byName(name) ?: return "There is no template \"$name\"."
        return if (AiTemplates.remove(t.id)) "Template \"" + t.name + "\" deleted." else "Could not delete."
    }

    private fun businessProfile(kind: String): String = buildString {
        append(AiBusiness.describe())
        if (kind.isNotBlank()) {
            append("\nNext number for \"").append(kind).append("\": ")
            append(AiBusiness.peekNumber(kind))
            append(" (it is assigned when the template is filled in — do not invent one).")
        }
    }

    /**
     * Fill an imported Word template.
     *
     * The archive is copied and only the text is substituted, so the user's
     * logo, fonts, tables and margins come out exactly as their accountant
     * signed off — which is the entire reason someone imports their own form
     * instead of letting the assistant design one.
     */
    private fun useDocxTemplate(
        a: MainActivity, t: AiTemplates.Template, values: JSONObject?, path: String
    ): String {
        val given = LinkedHashMap<String, String>()
        given.putAll(AiBusiness.autoValues(a, ""))
        values?.let { v ->
            val keys = v.keys()
            while (keys.hasNext()) { val k = keys.next(); given[k] = v.opt(k)?.toString().orEmpty() }
        }
        // a Word template carries its markers as literal {{name}} text
        val map = LinkedHashMap<String, String>()
        for ((k, value) in given) map["{{$k}}"] = value

        val preview = map.entries.take(20).joinToString("\n") { it.key + " → " + it.value }
        if (!confirmAlways(a, a.getString(R.string.ai_act_office_fill), t.name + "\n\n" + preview))
            return "The user refused."
        return try {
            val r = AiOffice.fillDocx(t.file, map, "-" + System.currentTimeMillis().toString().takeLast(5))
            AiWorkspace.index(a, r.out)
            AiActionLog.offerUndo(
                JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                    .put("path", r.out.absolutePath).put("backup", "")
            )
            buildString {
                append("Document ready: ").append(r.out.absolutePath)
                append("\nPlaceholders filled: ").append(r.replaced.size)
                if (r.notFound.isNotEmpty()) {
                    append("\nNot found in the template: ").append(r.notFound.take(8).joinToString(", "))
                }
                append("\nThe template itself was not modified.")
            }
        } catch (t2: Throwable) {
            "Could not fill in: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
        }
    }

    /**
     * Fill an imported PDF form.
     *
     * Same two routes as any other PDF: real form fields when the file has
     * them, stamping onto the page when it does not.
     */
    private fun usePdfTemplate(
        a: MainActivity, t: AiTemplates.Template, values: JSONObject?, path: String
    ): String {
        if (values == null || values.length() == 0)
            return "A PDF template needs values. " +
                "Look at pdf_fields or pdf_form_scan first, for the path " + t.file.absolutePath + "."
        val hasFields = try { AiPdf.fields(a, t.file).isNotEmpty() } catch (_: Throwable) { false }
        return if (hasFields) pdfFill(a, t.file.absolutePath, values, flatten = true)
        else pdfAutofill(a, t.file.absolutePath, values, 1, 10)
    }

    /**
     * Fill a template and produce the finished document.
     *
     * The user's own details, today's date and the next document number are
     * supplied here rather than asked for — that is the whole point of the
     * feature. A number is only CONSUMED when a document is actually produced,
     * so a refused confirmation does not burn an invoice number and leave a gap
     * in the sequence.
     */
    private fun useTemplate(
        a: MainActivity, name: String, values: JSONObject?, path: String,
        numberKind: String, sign: Boolean
    ): String {
        val t = AiTemplates.byName(name) ?: return "There is no template \"$name\". Check list_templates."

        // v1.7.73: an imported .docx or .pdf keeps its own format, so filling it
        // goes through the machinery that understands that format rather than
        // through HTML rendering, which would throw the user's layout away.
        if (t.isDocx) return useDocxTemplate(a, t, values, path)
        if (t.isPdf) return usePdfTemplate(a, t, values, path)

        var html = try { t.file.readText() } catch (_: Throwable) { return "Could not read the template." }

        // v1.7.75: a signature the user asked for goes in even when the template
        // has no slot for it — see AiTemplates.ensureSignature
        if (sign && AiBusiness.hasSignature(a)) {
            AiBusiness.signatureDataUri(a)?.let { img ->
                html = AiTemplates.ensureSignature(
                    html, img,
                    AiBusiness.get("person").ifBlank { AiBusiness.get("company") },
                    AiPdf.today()
                )
            }
        }

        val given = LinkedHashMap<String, String>()
        given.putAll(AiBusiness.autoValues(a, numberKind))
        values?.let { v ->
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                given[k] = v.opt(k)?.toString().orEmpty()
            }
        }

        val filled = AiTemplates.fill(html, given)
        if (filled.missing.isNotEmpty()) {
            // v1.7.74: this is where the model learns the template's fields, so
            // the user never has to be shown them. Said plainly, because the
            // model is expected to derive most of them from what the user wrote
            // rather than asking twenty-eight questions.
            return "The template still has unfilled fields: " + filled.missing.joinToString(", ") +
                ".\nMost of them follow from what the user has already said — " +
                "map their words onto these fields yourself (work out the amounts and totals). " +
                "Ask only for what genuinely cannot be derived, in one short question. " +
                "Then call this again."
        }

        // a mini-app is not printed; it is opened
        if (t.isApp) {
            val out = java.io.File(AiWorkspace.root(), "Apps/" + t.name + ".html")
            return try {
                out.parentFile?.mkdirs()
                out.writeText(filled.html)
                AiWorkspace.index(a, out)
                onMain(a) { act ->
                    act.tabs.newTab("file://" + out.absolutePath, false)
                    act.navigate("file://" + out.absolutePath)
                    AiAssistantSheet.minimizeForAction(act)
                }
                "Mini-application \"" + t.name + "\" opened in a tab.\n" + out.absolutePath
            } catch (t2: Throwable) {
                "Could not open: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
            }
        }

        val base = path.trim().removeSuffix(".pdf").ifBlank {
            "Documents/" + t.name + "-" + System.currentTimeMillis().toString().takeLast(6)
        }
        return when (val target = AiWorkspace.resolve("$base.pdf")) {
            is AiWorkspace.Target.Refused -> target.reason
            is AiWorkspace.Target.Ok -> {
                val preview = given.entries
                    .filter { it.key !in AiBusiness.all().keys }
                    .joinToString("\n") { "• " + it.key + ": " + it.value }
                val choice = AiWriteReview.review(
                    a,
                    title = target.file.name,
                    subtitle = a.getString(R.string.tpl_doc_sub, t.name),
                    before = null,
                    after = preview,
                    kindRes = R.string.rv_new,
                    blanketApplies = true
                )
                if (choice == AiWriteReview.Choice.REJECT ||
                    choice == AiWriteReview.Choice.REJECT_ALL
                ) return "The user refused."
                try {
                    val r = AiHtmlPdf.render(a, filled.html, target.file)
                    // only now is the number really used
                    val issued = if (numberKind.isNotBlank()) AiBusiness.nextNumber(numberKind) else ""
                    AiWorkspace.index(a, r.pdf)
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", r.pdf.absolutePath).put("backup", "")
                    )
                    AiPdf.pageImage(r.pdf, 1)?.let { pendingImage = it }
                    buildString {
                        append("Done: ").append(r.pdf.absolutePath)
                        if (issued.isNotEmpty()) append("\nDocument number: ").append(issued)
                        append("\nPages: ").append(r.pages)
                        append("\nThe first page will arrive as an image.")
                    }
                } catch (t2: Throwable) {
                    "Could not build the document: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
                }
            }
        }
    }


    // ---------------- v1.7.67: beautiful CVs ----------------

    /**
     * v1.7.68: the CV path that cannot break.
     *
     * Two things went wrong with "write me the HTML": it was slow, because the
     * model had to emit the whole page before anything happened, and it was
     * fragile, because one malformed tag loses the document. The layout now
     * lives in [CvBuilder] and the model sends data.
     *
     * When no template is given the user is asked with a picker — taste is the
     * one input a prompt cannot carry.
     */
    private fun makeCv(
        a: MainActivity, path: String, data: JSONObject?, template: String, accent: String,
        lang: String = ""
    ): String {
        if (data == null || data.length() == 0) return "No CV data given."
        var tpl = template.trim().lowercase()
        var color = accent.trim().ifBlank { "blue" }
        if (tpl.isBlank()) {
            val picked = CvStyleSheet.ask(a)
            if (picked != null) { tpl = picked.template; color = picked.accent }
            else tpl = "sidebar"   // the user said "decide for me"
        }

        val html = try { CvBuilder.build(tpl, color, data, lang) }
        catch (t: Throwable) { return "Could not build the CV: " + (t.localizedMessage ?: "") }

        val base = path.trim().removeSuffix(".pdf").removeSuffix(".html")
        return when (val t = AiWorkspace.resolve(base + ".pdf")) {
            is AiWorkspace.Target.Refused -> t.reason
            is AiWorkspace.Target.Ok -> {
                val choice = AiWriteReview.review(
                    a,
                    title = t.file.name,
                    subtitle = a.getString(R.string.cv_review_sub, tpl, color),
                    before = null,
                    after = summarize(data),
                    kindRes = R.string.rv_new,
                    blanketApplies = true
                )
                if (choice == AiWriteReview.Choice.REJECT ||
                    choice == AiWriteReview.Choice.REJECT_ALL
                ) return "The user refused."
                try {
                    val r = AiHtmlPdf.render(a, html, t.file)
                    AiWorkspace.index(a, r.pdf)
                    AiWorkspace.index(a, r.html)
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", r.pdf.absolutePath).put("backup", "")
                    )
                    AiPdf.pageImage(r.pdf, 1)?.let { pendingImage = it }
                    buildString {
                        append("CV ready: ").append(r.pdf.absolutePath)
                        append("\nTemplate: ").append(tpl).append(", colour: ").append(color)
                        append("\nPages: ").append(r.pages)
                        if (r.pages > 1) append(" — shorten the text and rebuild; a CV should fit on one.")
                        append("\nThe layout is saved alongside: ").append(r.html.absolutePath)
                        append("\nThe first page will arrive as an image — look at it.")
                    }
                } catch (t2: Throwable) {
                    // the .html is already written, so the work is not lost
                    "Could not lay out the PDF: " + (t2.localizedMessage ?: t2.javaClass.simpleName) +
                        "\nThe layout is still saved alongside as .html — it can be opened and printed by hand."
                }
            }
        }
    }

    /** What the review shows: the content, not the markup it will become. */
    private fun summarize(data: JSONObject): String {
        val sb = StringBuilder()
        sb.append(data.optString("name")).append('\n')
        data.optString("role").takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        sb.append('\n')
        data.optString("summary").takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
        for (key in listOf("contacts", "skills", "languages")) {
            val arr = data.optJSONArray(key) ?: continue
            if (arr.length() == 0) continue
            sb.append(key).append(": ")
            for (i in 0 until arr.length()) {
                if (i > 0) sb.append(" · ")
                sb.append(arr.opt(i)?.toString().orEmpty())
            }
            sb.append("\n")
        }
        for (key in listOf("experience", "education")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sb.append("\n").append(o.optString("title"))
                o.optString("where").takeIf { it.isNotBlank() }?.let { sb.append(" — ").append(it) }
                o.optString("when").takeIf { it.isNotBlank() }?.let { sb.append("  (").append(it).append(")") }
                sb.append('\n')
                val pts = o.optJSONArray("points") ?: continue
                for (j in 0 until pts.length()) sb.append("  • ").append(pts.opt(j)?.toString().orEmpty()).append('\n')
            }
        }
        return sb.toString()
    }

    private fun cvTemplate(name: String, lang: String = ""): String =
        if (name.isBlank()) CvTemplates.describe()
        else "Template \"" + name.trim().lowercase() + "\". Replace the content, leave the styling alone. " +
            "Write all the document text in the language \"" + DocLang.resolve(lang) + "\":\n\n" +
            CvTemplates.get(name, lang)

    /**
     * Where the user's own CV data lives.
     *
     * Tailoring needs a source of truth about the person that is not the
     * current conversation, or every new chat starts by asking for their whole
     * career again. A base CV in the workspace plus the assistant's memory
     * covers it, and both are things the user can read and correct.
     */
    private fun tailorCv(a: MainActivity, note: String): String {
        val sb = StringBuilder()
        val root = AiWorkspace.root()
        val cvDir = java.io.File(root, "CV")
        val candidates = ArrayList<java.io.File>()
        for (dir in listOf(cvDir, root)) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.contains("cv", true) &&
                    (f.extension.equals("md", true) || f.extension.equals("txt", true) ||
                        f.extension.equals("html", true))
                ) candidates.add(f)
            }
        }
        val base = candidates.maxByOrNull { it.lastModified() }
        if (base != null) {
            sb.append("The user's base CV (").append(base.absolutePath).append("):\n")
            sb.append(try { base.readText().take(6000) } catch (_: Throwable) { "" })
            sb.append("\n\n")
        } else {
            sb.append("No base CV was found in the workspace. ")
            sb.append("Ask the user for their experience, education, skills and languages, ")
            sb.append("then save them to CV/base.md with write_file — next time ")
            sb.append("there will be no need to ask again.\n\n")
        }

        val facts = AiMemory.all()
        if (facts.isNotEmpty()) {
            sb.append("What is known about the user:\n")
            for (f in facts) sb.append("• ").append(f.text).append('\n')
            sb.append('\n')
        }
        if (note.isNotBlank()) sb.append("What the user asked for: ").append(note).append("\n\n")

        sb.append("Next: read the advert, pick a layout with cv_template, ")
        sb.append("rewrite the experience in the advert's own words — but only what ")
        sb.append("the user actually has — and save it with render_html. ")
        sb.append("If there is no experience for one of the requirements, say so to the user ")
        sb.append("rather than inventing it.")
        return sb.toString()
    }

    private fun renderHtml(a: MainActivity, path: String, html: String): String {
        if (html.isBlank()) return "Empty layout."
        if (!html.contains("<")) return "That is not HTML."
        val base = path.trim().removeSuffix(".pdf").removeSuffix(".html")
        return when (val t = AiWorkspace.resolve(base + ".pdf")) {
            is AiWorkspace.Target.Refused -> t.reason
            is AiWorkspace.Target.Ok -> {
                val choice = AiWriteReview.review(
                    a,
                    title = t.file.name,
                    subtitle = (t.file.parent ?: "") + "/",
                    before = null,
                    after = html,
                    kindRes = R.string.rv_new,
                    blanketApplies = true
                )
                if (choice == AiWriteReview.Choice.REJECT ||
                    choice == AiWriteReview.Choice.REJECT_ALL
                ) return "The user refused."
                try {
                    val r = AiHtmlPdf.render(a, html, t.file)
                    AiWorkspace.index(a, r.pdf)
                    AiWorkspace.index(a, r.html)
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", r.pdf.absolutePath).put("backup", "")
                    )
                    // the first page comes back as an image, so the model sees
                    // what it actually produced instead of hoping
                    AiPdf.pageImage(r.pdf, 1)?.let { pendingImage = it }
                    buildString {
                        append("Done: ").append(r.pdf.absolutePath)
                        append("\nPages: ").append(r.pages)
                        if (r.pages > 1) append(" — that is too many for a CV; shorten the text and rebuild.")
                        append("\nThe layout is saved alongside: ").append(r.html.absolutePath)
                        append("\nThe first page will arrive as an image — look at how it came out.")
                    }
                } catch (t2: Throwable) {
                    "Could not lay it out: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * v1.7.63: one CV, two files.
     *
     * The request behind this was "make good-looking CVs and convert docx
     * to pdf, to print". Converting an arbitrary .docx faithfully needs
     * a layout engine nobody can fit in a browser; producing both formats from
     * the same content does not, and answers the same need — a file to send and
     * a file to print, which match.
     */
    private fun makeDocument(a: MainActivity, path: String, content: String, wantPdf: Boolean): String {
        if (content.isBlank()) return "Empty content."
        val base = path.trim().removeSuffix(".docx").removeSuffix(".pdf")
        return when (val t = AiWorkspace.resolve("$base.docx")) {
            is AiWorkspace.Target.Refused -> t.reason
            is AiWorkspace.Target.Ok -> {
                val choice = AiWriteReview.review(
                    a,
                    title = t.file.name,
                    subtitle = (t.file.parent ?: "") + "/",
                    before = null,
                    after = content,
                    kindRes = R.string.rv_new,
                    blanketApplies = true
                )
                if (choice == AiWriteReview.Choice.REJECT ||
                    choice == AiWriteReview.Choice.REJECT_ALL
                ) return "The user refused."
                try {
                    val blocks = AiDocBuilder.parse(content)
                    AiDocBuilder.writeDocx(t.file, blocks)
                    AiWorkspace.index(a, t.file)
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", t.file.absolutePath).put("backup", "")
                    )
                    val sb = StringBuilder("Document created: ").append(t.file.absolutePath)
                    if (wantPdf) {
                        val pdf = java.io.File(t.file.parentFile, t.file.nameWithoutExtension + ".pdf")
                        try {
                            AiDocBuilder.writePdf(a, pdf, blocks)
                            AiWorkspace.index(a, pdf)
                            sb.append("\nPDF for printing: ").append(pdf.absolutePath)
                        } catch (t2: Throwable) {
                            // the .docx already exists and is the important one
                            sb.append("\nThe PDF could not be built: ")
                                .append(t2.localizedMessage ?: t2.javaClass.simpleName)
                        }
                    }
                    sb.append("\nIt can be opened and checked in the assistant's Projects screen.")
                    sb.toString()
                } catch (t2: Throwable) {
                    "Could not create the document: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
                }
            }
        }
    }

    // ---------------- v1.7.62: Word / Excel / PowerPoint ----------------

    private fun officeRead(a: MainActivity, path: String): String {
        val f = pdfFile(path) ?: return "Document not found: $path"
        return try {
            val d = AiOffice.read(f)
            buildString {
                append("Document: ").append(f.name).append(" (").append(d.kind).append(")\n\n")
                append(d.note ?: d.text)
                if (d.truncated) append("\n\n… text truncated.")
            }
        } catch (t: Throwable) {
            t.localizedMessage ?: ("Could not read: " + t.javaClass.simpleName)
        }
    }

    private fun officeFill(a: MainActivity, path: String, values: JSONObject?): String {
        val f = pdfFile(path) ?: return "Document not found: $path"
        if (values == null || values.length() == 0) return "Nothing given to substitute."
        val map = LinkedHashMap<String, String>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = values.opt(k)?.toString().orEmpty()
        }
        val preview = map.entries.joinToString("\n") { "• " + it.key + " → " + it.value }
        if (!confirmAlways(a, a.getString(R.string.ai_act_office_fill), f.name + "\n\n" + preview))
            return "The user refused."
        return try {
            val r = AiOffice.fillDocx(f, map)
            AiWorkspace.index(a, r.out)
            AiActionLog.offerUndo(
                JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                    .put("path", r.out.absolutePath).put("backup", "")
            )
            buildString {
                append("Done: ").append(r.out.absolutePath)
                append("\nSubstituted: ").append(r.replaced.size)
                if (r.notFound.isNotEmpty()) {
                    // Word splits runs where it recorded an edit, so a
                    // placeholder can be physically cut in half in the file
                    append("\nNot found in the document: ").append(r.notFound.joinToString(", "))
                    append("\nCheck the exact spelling — Word may have split that text across edits. ")
                    append("Call office_read first and take the string from there.")
                }
                append("\nThe original file was not modified.")
            }
        } catch (t: Throwable) {
            t.localizedMessage ?: ("Could not fill in: " + t.javaClass.simpleName)
        }
    }

    private fun officeCreate(a: MainActivity, path: String, content: String): String {
        if (content.isBlank()) return "Empty content."
        return when (val t = AiWorkspace.resolve(path)) {
            is AiWorkspace.Target.Refused -> t.reason
            is AiWorkspace.Target.Ok -> {
                val ext = t.file.extension.lowercase()
                if (ext != "docx" && ext != "xlsx")
                    return "The file name must end in .docx or .xlsx"
                val choice = AiWriteReview.review(
                    a,
                    title = t.file.name,
                    subtitle = (t.file.parent ?: "") + "/",
                    before = null,
                    after = content,
                    kindRes = R.string.rv_new,
                    blanketApplies = true
                )
                if (choice == AiWriteReview.Choice.REJECT ||
                    choice == AiWriteReview.Choice.REJECT_ALL
                ) return "The user refused."
                try {
                    t.file.parentFile?.mkdirs()
                    if (ext == "docx") AiOffice.createDocx(t.file, content)
                    else AiOffice.createXlsx(t.file, content.split("\n").map { line ->
                        // a table can arrive tab- or semicolon-separated; both
                        // are what a model reaches for, so both are accepted
                        if (line.contains('\t')) line.split('\t') else line.split(';')
                    })
                    AiWorkspace.index(a, t.file)
                    AiActionLog.offerUndo(
                        JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                            .put("path", t.file.absolutePath).put("backup", "")
                    )
                    "Document created: " + t.file.absolutePath
                } catch (t2: Throwable) {
                    "Could not create: " + (t2.localizedMessage ?: t2.javaClass.simpleName)
                }
            }
        }
    }

    // ---------------- v1.7.61: PDF documents ----------------

    /**
     * A PDF path is resolved leniently: unlike a write, READING one has to work
     * on documents the user already has — a bank form in Downloads, an
     * attachment somewhere else — not only inside the assistant's workspace.
     * Writes still land next to the source, which is why pdf_fill and pdf_stamp
     * are behind the same switch as any other file creation.
     */
    private fun pdfFile(path: String): java.io.File? {
        val raw = path.trim()
        if (raw.isBlank()) return null
        val direct = java.io.File(raw)
        if (direct.isFile) return direct
        return when (val t = AiWorkspace.resolve(raw)) {
            is AiWorkspace.Target.Ok -> t.file.takeIf { it.isFile }
            else -> null
        }
    }

    private fun pdfRead(a: MainActivity, path: String, from: Int, to: Int): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        return try {
            val d = AiPdf.read(a, f, if (from > 0) from else 1, to)
            buildString {
                append("Document: ").append(f.name)
                append(", pages: ").append(d.pages)
                if (d.hasForm) append(". This document DOES have fillable fields — see pdf_fields.")
                append("\n\n")
                append(if (d.text.isBlank())
                    "No text found. This is most likely a scan — the text is drawn as an image " +
                        "rather than stored as characters, and cannot be read from here."
                else d.text)
                if (d.truncated) append("\n\n… text truncated; ask for specific pages.")
            }
        } catch (t: Throwable) {
            "Could not read the PDF: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun pdfFields(a: MainActivity, path: String): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        return try {
            val list = AiPdf.fields(a, f)
            if (list.isEmpty())
                "This PDF has no fillable fields. Text can be stamped in with pdf_stamp."
            else buildString {
                append("Fields of the document ").append(f.name).append(":\n")
                for ((i, fl) in list.withIndex()) {
                    append(i + 1).append(". ").append(fl.name)
                    append("  [").append(fl.type).append(']')
                    if (fl.readOnly) append(" (read-only)")
                    if (fl.value.isNotBlank()) append("\n   current: ").append(fl.value)
                    if (fl.options.isNotEmpty())
                        append("\n   options: ").append(fl.options.joinToString(", "))
                    append('\n')
                }
                append("\nCopy the field names from here exactly.")
            }
        } catch (t: Throwable) {
            "Could not parse the form: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun pdfFill(a: MainActivity, path: String, values: JSONObject?, flatten: Boolean): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        if (values == null || values.length() == 0) return "Nothing given to fill in."
        val map = LinkedHashMap<String, String>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = values.opt(k)?.toString().orEmpty()
        }
        // a filled form is a document the user will send somewhere, so it is
        // always confirmed and always shows exactly what goes into which field
        val preview = map.entries.joinToString("\n") { "• " + it.key + " → " + it.value }
        if (!confirmAlways(a, a.getString(R.string.ai_act_pdf_fill), f.name + "\n\n" + preview))
            return "The user refused."
        return try {
            val r = AiPdf.fill(a, f, map, flatten)
            AiWorkspace.index(a, r.out)
            AiActionLog.offerUndo(
                JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                    .put("path", r.out.absolutePath).put("backup", "")
            )
            buildString {
                append("Done: ").append(r.out.absolutePath)
                append("\nFields filled: ").append(r.filled.size)
                if (r.missing.isNotEmpty())
                    append("\nFailed: ").append(r.missing.joinToString(", "))
                if (flatten) append("\nThe values are stamped in permanently.")
                append("\nThe original file was not modified.")
            }
        } catch (t: Throwable) {
            "Could not fill in: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /**
     * v1.7.63: every mark on the page in ONE call, and anchors instead of
     * invented coordinates.
     *
     * Both failures the user saw came from here. One model wrote each value
     * with a separate call, so each output was built from the blank original
     * and kept only its own word. The other guessed x/y and landed a name on
     * top of the label "Commune". A list of marks fixes the first; `after`
     * fixes the second.
     */
    private fun pdfStamp(a: MainActivity, path: String, args: JSONObject): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"

        val marks = ArrayList<AiPdf.Mark>()
        val items = args.optJSONArray("items")
        if (items != null && items.length() > 0) {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val text = o.optString("text").trim().ifBlank { AiPdf.today() }
                marks.add(
                    AiPdf.Mark(
                        text = text,
                        page = o.optInt("page", 1),
                        xPercent = if (o.has("x")) o.optDouble("x", -1.0).toFloat() else -1f,
                        yPercent = if (o.has("y")) o.optDouble("y", -1.0).toFloat() else -1f,
                        size = o.optDouble("size", 11.0).toFloat(),
                        after = o.optString("after"),
                        dx = o.optDouble("dx", 1.0).toFloat(),
                        dy = o.optDouble("dy", 0.0).toFloat()
                    )
                )
            }
        } else {
            // the single-mark form still works, for one date or one signature
            marks.add(
                AiPdf.Mark(
                    text = args.optString("text").trim().ifBlank { AiPdf.today() },
                    page = args.optInt("page", 1),
                    xPercent = if (args.has("x")) args.optDouble("x", -1.0).toFloat() else -1f,
                    yPercent = if (args.has("y")) args.optDouble("y", -1.0).toFloat() else -1f,
                    size = args.optDouble("size", 11.0).toFloat(),
                    after = args.optString("after"),
                    dx = args.optDouble("dx", 1.0).toFloat(),
                    dy = args.optDouble("dy", 0.0).toFloat()
                )
            )
        }
        if (marks.isEmpty()) return "Nothing given to stamp."

        val preview = marks.joinToString("\n") { m ->
            "• " + m.text + (if (m.after.isNotBlank()) "  → after \"" + m.after + "\""
            else "  → " + m.xPercent.toInt() + "%, " + m.yPercent.toInt() + "%")
        }
        if (!confirmAlways(a, a.getString(R.string.ai_act_pdf_stamp), f.name + "\n\n" + preview))
            return "The user refused."

        return try {
            val out = AiPdf.stampAll(a, f, marks)
            AiWorkspace.index(a, out)
            AiActionLog.offerUndo(
                JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                    .put("path", out.absolutePath).put("backup", "")
            )
            buildString {
                append("Done: ").append(out.absolutePath)
                append("\nPieces stamped: ").append(marks.size)
                if (marks.any { AiPdf.needsTranslit(it.text) } && !AiPdf.unicodeAvailable()) {
                    append("\nWARNING: no font with Cyrillic was found on this device, ")
                    append("so the text was stamped in Latin letters.")
                }
                append("\nThe original file was not modified. ")
                append("Check the result with pdf_view — if anything landed in the wrong place, ")
                append("correct the coordinates and stamp the original again.")
            }
        } catch (t: Throwable) {
            "Could not stamp: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /**
     * v1.7.66: list the blanks, not the labels.
     *
     * Every tool that fills paper forms reliably works this way — find where
     * writing goes, number it, fill by number. Matching label text at fill time
     * is what produced two values fighting over one "Nom de naissance".
     */
    private fun pdfFormScan(a: MainActivity, path: String, page: Int): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        return try {
            val fields = AiPdf.scanFields(a, f, page)
            if (fields.isEmpty())
                return "No blanks to fill are visible on the page. " +
                    "Look at pdf_layout and place values with \"text@x,y\"."
            buildString {
                append("Blanks to fill on page ").append(if (page > 0) page else 1).append(":\n")
                for (fl in fields) {
                    append("#").append(fl.index).append("  \"").append(fl.label).append("\"")
                    if (fl.occurrence > 1) append("  (occurrence ").append(fl.occurrence).append(")")
                    append("   y ").append(fl.yPercent.toInt()).append("%, room for ")
                    append(fl.widthPercent.toInt()).append("%\n")
                }
                append("\nFill them with pdf_autofill by number: {\"#1\": \"value\", \"#2\": \"…\"}. ")
                append("The numbers tell identical labels apart — use them ")
                append("whenever the same label appears twice.")
            }
        } catch (t: Throwable) {
            "Could not parse the form: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /**
     * v1.7.64: fill a whole paper form in ONE tool call.
     *
     * The user watched a model spend an entire answer on this and still run out
     * of steps: read the layout, look at the page, stamp, look again, stamp
     * again. Every one of those is a round trip, and eight of them is the
     * budget for a complete answer.
     *
     * The work itself is not expensive — it is the conversation about it that
     * was. So the whole sequence lives here: read the page's text positions,
     * match each label, place every value after its label, save once, and hand
     * back a rendered image so the model can verify without another call.
     */
    private fun pdfAutofill(
        a: MainActivity, path: String, values: JSONObject?, page: Int, size: Int
    ): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        if (values == null || values.length() == 0) return "Nothing given to fill in."

        val marks = ArrayList<AiPdf.Mark>()
        val unmatched = ArrayList<String>()
        val preview = StringBuilder()
        // where each value ended up, reported back so the model can correct
        // itself instead of guessing why something looks wrong
        val placements = StringBuilder()
        val usedRows = ArrayList<Pair<Float, Float>>()

        // both the layout and the detected blanks are read once here, so the
        // model never has to spend a round asking for either
        val lines = try { AiPdf.layout(a, f, page) } catch (_: Throwable) { emptyList() }
        val scanned = try { AiPdf.scanFields(a, f, page) } catch (_: Throwable) { emptyList() }

        val keys = values.keys()
        while (keys.hasNext()) {
            val label = keys.next()
            val rawValue = values.opt(label)?.toString().orEmpty()
            if (rawValue.isBlank()) continue

            // "text@x,y" is the escape hatch for a blank with no printed label
            val at = Regex("""^(.*)@\s*(\d{1,3})\s*,\s*(\d{1,3})\s*$""").find(rawValue)
            if (at != null) {
                marks.add(
                    AiPdf.Mark(
                        text = at.groupValues[1].trim(),
                        page = if (page > 0) page else 1,
                        xPercent = at.groupValues[2].toFloat(),
                        yPercent = at.groupValues[3].toFloat(),
                        size = (if (size > 0) size else 10).toFloat()
                    )
                )
                preview.append("• ").append(at.groupValues[1].trim())
                    .append("  → ").append(at.groupValues[2]).append("%, ")
                    .append(at.groupValues[3]).append("%\n")
                continue
            }

            // v1.7.66: "#3" addresses a blank found by pdf_form_scan. This is
            // the reliable path — a number cannot be ambiguous the way a label
            // repeated twice on the same form is.
            val byNumber = Regex("""^#(\d+)$""").find(label.trim())
            if (byNumber != null) {
                val n = byNumber.groupValues[1].toIntOrNull() ?: 0
                val fld = scanned.firstOrNull { it.index == n }
                if (fld == null) { unmatched.add(label); continue }
                marks.add(
                    AiPdf.Mark(
                        text = rawValue,
                        page = if (page > 0) page else 1,
                        // start of the blank itself, nudged clear of the label
                        xPercent = fld.xPercent + 0.6f,
                        yPercent = fld.yPercent,
                        size = (if (size > 0) size else 10).toFloat()
                    )
                )
                placements.append("• #").append(n).append(" «").append(fld.label)
                    .append("» → x ").append(fld.xPercent.toInt())
                    .append("%, y ").append(fld.yPercent.toInt()).append("%\n")
                preview.append("• ").append(rawValue).append("  → field #").append(n)
                    .append(" («").append(fld.label).append("»)\n")
                continue
            }

            // v1.7.65: the same scoring the stamper uses, so the preview shows
            // the line that will actually receive the value
            val hit = AiPdf.bestAnchor(lines, label)
            if (hit == null) {
                unmatched.add(label)
                continue
            }
            // prefer the blank belonging to this label over the raw line end:
            // the blank is where the form means writing to go
            val blank = scanned.firstOrNull {
                kotlin.math.abs(it.yPercent - hit.yPercent) < 1.2f &&
                    label.substringBefore(0x23.toChar()).trim().let { l ->
                        it.label.contains(l, true) || l.contains(it.label, true)
                    }
            }
            // two values anchored to the same line would sit on top of each
            // other; the later one steps down to the next free baseline
            val baseY = hit.yPercent
            var dy = 0f
            while (usedRows.any { kotlin.math.abs(it.first - (baseY + dy)) < 1.2f &&
                    kotlin.math.abs(it.second - hit.endOfLabel(label)) < 12f }
            ) dy += 2.2f
            usedRows.add((baseY + dy) to hit.endOfLabel(label))

            marks.add(
                AiPdf.Mark(
                    text = rawValue,
                    page = if (page > 0) page else 1,
                    after = if (blank == null) label else "",
                    xPercent = blank?.let { it.xPercent + 0.6f } ?: -1f,
                    yPercent = blank?.yPercent ?: -1f,
                    size = (if (size > 0) size else 10).toFloat(),
                    dy = if (blank == null) dy else 0f
                )
            )
            placements.append("• \"").append(label).append("\" → line \"")
                .append(hit.text.take(48)).append("», x ")
                .append(hit.endOfLabel(label).toInt()).append("%, y ")
                .append((baseY + dy).toInt()).append("%\n")
            preview.append("• ").append(rawValue).append("  → after \"").append(label).append("\"\n")
        }

        if (marks.isEmpty()) {
            return "None of the labels were found on page " + (if (page > 0) page else 1) + ": " +
                unmatched.joinToString(", ") +
                ".\nCall pdf_layout and take the labels from there exactly, " +
                "or give the values as \"text@x,y\" in percentages."
        }

        if (!confirmAlways(a, a.getString(R.string.ai_act_pdf_fill), f.name + "\n\n" + preview))
            return "The user refused."

        return try {
            val out = AiPdf.stampAll(a, f, marks)
            AiWorkspace.index(a, out)
            AiActionLog.offerUndo(
                JSONObject().put("kind", AiActionLog.Kind.FILE_WRITE)
                    .put("path", out.absolutePath).put("backup", "")
            )
            // the filled page comes back as an image, so checking the result
            // costs nothing extra
            AiPdf.pageImage(out, if (page > 0) page else 1)?.let { pendingImage = it }
            buildString {
                append("Done: ").append(out.absolutePath)
                append("\nValues written: ").append(marks.size)
                if (unmatched.isNotEmpty()) {
                    append("\nLabels not found: ").append(unmatched.joinToString(", "))
                    append(" — these can be added in a second call using \"text@x,y\".")
                }
                if (placements.isNotEmpty()) append("\n\nWhere each one landed:\n").append(placements)
                append("\nThe original file was not modified. The filled page arrives as an image — check it. ")
                append("If anything is in the wrong place, call this again on the ORIGINAL file, ")
                append("giving those values as \"text@x,y\" in percentages.")
            }
        } catch (t: Throwable) {
            "Could not fill in: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /** Where every line sits on the page — the basis for `after` anchors. */
    private fun pdfLayout(a: MainActivity, path: String, page: Int): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        return try {
            val lines = AiPdf.layout(a, f, page)
            if (lines.isEmpty()) return "There is no text on this page — it may be a scan."
            buildString {
                append("Layout of page ").append(if (page > 0) page else 1)
                append(" (coordinates as percentages, x left to right, y top to bottom):\n")
                for (l in lines.take(120)) {
                    append("• «").append(l.text.take(70)).append("»")
                    append("  x ").append(l.xPercent.toInt()).append("–").append(l.xEndPercent.toInt())
                    append(", y ").append(l.yPercent.toInt()).append('\n')
                }
                append("\nTo write a value next to a label, pass pdf_stamp ")
                append("`after` with an exact piece of that line — the coordinates follow.")
            }
        } catch (t: Throwable) {
            "Could not parse the page: " + (t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /** Render a page and hand it to the model as an image. */
    private fun pdfView(path: String, page: Int): String {
        val f = pdfFile(path) ?: return "PDF not found: $path"
        val data = AiPdf.pageImage(f, if (page > 0) page else 1)
            ?: return "Could not render the page."
        pendingImage = data
        return "Page " + (if (page > 0) page else 1) + " of " + f.name +
            " has been rendered and arrives as an image in the next message."
    }


    // ---------------- v1.7.54: tasks & reminders ----------------

    /** Local "now" handed to the model, because it has no clock of its own. */
    private fun nowStamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm, EEEE", java.util.Locale.getDefault()
    ).format(java.util.Date())

    /**
     * Turn the model's [raw] time into an instant.
     *
     * Absolute first, because that is what the tool asks for; the relative and
     * time-only forms exist because models reach for them anyway, and guessing
     * wrong once means a reminder at the wrong hour. A bare time already past
     * rolls to tomorrow rather than firing immediately.
     */
    private fun parseWhen(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank()) return null

        Regex("""^\+(\d+)\s*([mhdмчд])""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = m.groupValues[2].lowercase()
            val ms = when (unit) {
                "m", "м" -> n * 60_000
                "h", "ч" -> n * 3_600_000
                else -> n * 86_400_000
            }
            return System.currentTimeMillis() + ms
        }

        val patterns = listOf(
            "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss", "dd.MM.yyyy HH:mm", "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val f = java.text.SimpleDateFormat(p, java.util.Locale.US)
                f.isLenient = false
                val d = f.parse(s) ?: continue
                // a bare date means "that day", not midnight-as-a-surprise: 09:00
                if (p == "yyyy-MM-dd") {
                    val c = java.util.Calendar.getInstance().apply {
                        time = d; set(java.util.Calendar.HOUR_OF_DAY, 9)
                    }
                    return c.timeInMillis
                }
                return d.time
            } catch (_: Throwable) {}
        }

        Regex("""^(\d{1,2})[:.](\d{2})$""").find(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, min)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis
        }
        return null
    }

    /**
     * The Russian words here are deliberate and must stay.
     *
     * v1.9.5: this file was translated to English, and these are the one thing
     * that was left. They are not output — they are INPUT the assistant is
     * expected to recognise. A Russian-speaking user says "напомни каждый
     * день", the model passes that straight through as `repeat`, and it has to
     * land on DAILY. Translate them away and the phrase stops working, which
     * would be a regression dressed up as tidying.
     *
     * The same applies to the `м`/`ч`/`д` suffixes in [parseWhen] above, for
     * relative times such as `+30м`.
     */
    private fun repeatCode(raw: String): Int = when (raw.trim().lowercase()) {
        "daily", "day", "каждый день", "ежедневно" -> Reminders.DAILY
        "weekdays", "workdays", "будни" -> Reminders.WEEKDAYS
        "weekly", "week", "каждую неделю", "еженедельно" -> Reminders.WEEKLY
        "monthly", "month", "каждый месяц", "ежемесячно" -> Reminders.MONTHLY
        else -> Reminders.ONCE
    }

    private fun addReminder(
        a: MainActivity, title: String, whenRaw: String,
        notes: String, repeatRaw: String, url: String, alarm: Boolean
    ): String {
        if (title.isBlank()) return "No subject given for the reminder."
        val at = parseWhen(whenRaw)
            ?: return "Could not read the time \"$whenRaw\". The time now is " + nowStamp() +
                ". Give it as YYYY-MM-DD HH:MM."
        if (at < System.currentTimeMillis() - 60_000)
            return "That time has already passed (" + Reminders.whenText(a, at) + "). The time now is " + nowStamp() + "."
        val repeat = repeatCode(repeatRaw)
        val row = com.zephyr.browser.db.ReminderEntity(
            title = title.trim(),
            notes = notes.trim(),
            dueAt = at,
            repeat = repeat,
            alarm = alarm,
            url = if (url.startsWith("http", true)) url.trim() else "",
            source = "ai"
        )
        return runBlocking {
            try {
                val app = a.applicationContext
                val id = AppDb.get(app).reminderDao().insert(row)
                com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
                Reminders.schedule(app, row.copy(id = id))
                AiActionLog.offerUndo(JSONObject().put("kind", AiActionLog.Kind.REMINDER).put("id", id))
                val notif = try {
                    if (androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()) ""
                    else "\nWARNING: notifications for Zephyr are switched off — the reminder will not appear. " +
                        "Turn them on from the warning at the top of the Tasks screen."
                } catch (_: Throwable) { "" }
                val exact = if (Reminders.canBeExact(app)) ""
                else "\nWARNING: exact alarms are not permitted by the system — this may fire up to 5 minutes late. " +
                    "It is enabled from the warning at the top of the Tasks screen."
                "Reminder #" + id + " set: \"" + row.title + "\" — " +
                    Reminders.whenText(a, at) +
                    (if (repeat != Reminders.ONCE) ", repeats: " + Reminders.repeatLabel(a, repeat) else "") +
                    notif + exact
            } catch (t: Throwable) {
                "Could not set the reminder: " + (t.localizedMessage ?: t.javaClass.simpleName)
            }
        }
    }

    private fun listReminders(a: MainActivity, days: Int): String = runBlocking {
        try {
            val now = System.currentTimeMillis()
            val until = now + days.coerceIn(1, 365) * 86_400_000L
            val rows = AppDb.get(a.applicationContext).reminderDao().allOnce()
                .filter { it.dueAt <= until }
                .sortedBy { it.dueAt }
                .take(MAX_ROWS)
            if (rows.isEmpty()) "No tasks in the next $days days."
            else rows.joinToString("\n") { r ->
                "#" + r.id + " " + (if (r.done) "[done] " else "") + r.title +
                    "\n  " + Reminders.whenText(a, r.dueAt) +
                    (if (r.repeat != Reminders.ONCE) " · " + Reminders.repeatLabel(a, r.repeat) else "") +
                    (if (r.notes.isNotBlank()) "\n  " + r.notes else "")
            }
        } catch (t: Throwable) { "Tasks unavailable: " + t.localizedMessage }
    }

    private fun completeReminder(a: MainActivity, id: Long): String = runBlocking {
        if (id < 0) return@runBlocking "No task number given."
        try {
            val app = a.applicationContext
            val dao = AppDb.get(app).reminderDao()
            val r = dao.byId(id) ?: return@runBlocking "There is no task #$id. Check list_reminders."
            val out = if (r.repeat == Reminders.ONCE) {
                dao.update(r.copy(done = true))
                Reminders.cancel(app, r.id)
                "Task #$id marked done: " + r.title
            } else {
                // repeating tasks do not end — they move on
                val next = Reminders.nextAfterNow(r.dueAt, r.repeat)
                dao.update(r.copy(dueAt = next))
                Reminders.schedule(app, r.copy(dueAt = next))
                "Task #$id closed for today; the next one is " + Reminders.whenText(a, next)
            }
            // v1.9.14: the assistant changed the list, so the assistant repaints
            // the widget — add_reminder already did, these two did not
            com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            out
        } catch (t: Throwable) { "Did not work: " + t.localizedMessage }
    }

    private fun deleteReminder(a: MainActivity, id: Long): String = runBlocking {
        if (id < 0) return@runBlocking "No task number given."
        try {
            val app = a.applicationContext
            val dao = AppDb.get(app).reminderDao()
            val r = dao.byId(id) ?: return@runBlocking "There is no task #$id."
            if (!confirmAlways(a, a.getString(R.string.ai_act_del_task),
                    r.title + "\n" + Reminders.whenText(a, r.dueAt))
            ) return@runBlocking "The user refused."
            Reminders.cancel(app, r.id)
            dao.delete(r)
            com.zephyr.browser.widget.TasksWidgetProvider.refreshAll(app)
            "Task #$id deleted: " + r.title
        } catch (t: Throwable) { "Did not work: " + t.localizedMessage }
    }

    // ---------------- model profiles ----------------

    /**
     * v1.7.42: the assistant can set ITSELF up (user: "if I tell the agent to
     * add such-and-such an API key to a new profile, can it?").
     *
     * Keys go IN but never come OUT: [listAiModels] reports only whether a key
     * is present. Otherwise any model in the swarm could read the others'
     * credentials and put them in an answer.
     */
    private fun listAiModels(): String {
        val items = AiModels.list()
        if (items.isEmpty()) return "No model profiles."
        val active = AiModels.activeIndex()
        return items.mapIndexed { i, p ->
            (if (i == active) "● " else "○ ") + p.name +
                "\n  model: " + p.model +
                "\n  address: " + p.endpoint +
                "\n  key: " + (if (p.key.isNotBlank()) "set" else "not set")
        }.joinToString("\n")
    }

    private fun addAiModel(name: String, endpoint: String, model: String, key: String): String {
        if (endpoint.isBlank() || model.isBlank()) return "Both an address and a model identifier are required."
        val p = AiProfile(
            name = name.ifBlank { model },
            endpoint = endpoint.trim(),
            model = model.trim(),
            key = key.trim()
        )
        val items = AiModels.list().toMutableList()
        val same = items.indexOfFirst {
            it.endpoint.equals(p.endpoint, true) && it.model.equals(p.model, true)
        }
        if (same >= 0) {
            items[same] = p
            AiModels.save(items, same)
            return "Profile \"" + p.name + "\" updated."
        }
        items.add(p)
        AiModels.save(items, items.size - 1)
        AiActionLog.offerUndo(JSONObject().put("kind", AiActionLog.Kind.MODEL).put("name", p.name))
        return "Profile \"" + p.name + "\" added and made active."
    }

    private fun useAiModel(name: String): String {
        val items = AiModels.list()
        val idx = items.indexOfFirst { it.name.equals(name, true) }
        if (idx < 0) return "There is no profile \"" + name + "\". Check list_ai_models."
        Prefs.setSwarmEnabled(false)
        AiModels.setActive(idx)
        return "Active model: " + items[idx].name
    }

    /** Confirmation that ignores the «trust» switch — for credentials. */
    private fun confirmAlways(a: MainActivity, title: String, body: String): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try {
                MaterialAlertDialogBuilder(a)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(R.string.ok) { _, _ -> ok = true; latch.countDown() }
                    .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(120, TimeUnit.SECONDS)
        return ok
    }

    // ---------------- plumbing ----------------

    /** Run [block] on the UI thread and wait for its result. */
    private fun <T> onMain(a: MainActivity, block: (MainActivity) -> T): T? {
        var result: T? = null
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try { result = block(a) } catch (_: Throwable) {} finally { latch.countDown() }
        }
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    /** Evaluate JS in the current tab and return the unwrapped string result. */
    private fun evalJs(js: String, timeoutSec: Long = 15): String? {
        val a = host ?: return null
        var out: String? = null
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            val wv = a.tabs.current?.webView
            if (wv == null) latch.countDown()
            else try {
                wv.evaluateJavascript(js) { r -> out = r; latch.countDown() }
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return out?.let { unquote(it) }
    }

    /** evaluateJavascript hands back a JSON literal — turn it into a plain string. */
    private fun unquote(raw: String): String {
        if (raw == "null") return ""
        if (!raw.startsWith("\"")) return raw
        return try {
            JSONArray("[$raw]").optString(0)
        } catch (_: Throwable) {
            raw.trim('"').replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }

    /** JS string literal — never build one by concatenation. */
    private fun q(s: String): String = JSONObject.quote(s)

    /** Ask before an action that changes something. */
    private fun confirm(a: MainActivity, title: String, body: String): Boolean {
        if (Prefs.aiTrustActions) return true
        var ok = false
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try {
                MaterialAlertDialogBuilder(a)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(R.string.ok) { _, _ -> ok = true; latch.countDown() }
                    .setNegativeButton(R.string.cancel) { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(90, TimeUnit.SECONDS)
        return ok
    }

    private fun shortHost(url: String): String = try {
        android.net.Uri.parse(url).host?.removePrefix("www.") ?: url.take(30)
    } catch (_: Throwable) { url.take(30) }

    private fun human(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun date(ts: Long): String =
        java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ts))
}
