package com.wisperlow.mobile.keyboard

import androidx.annotation.StringRes
import com.wisperlow.mobile.R

/** One tab of the emoji panel. */
class EmojiCategory(@StringRes val label: Int, val icon: String, val emoji: List<String>)

object Emoji {
    private fun list(s: String) = s.trim().split(Regex("\\s+"))

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            R.string.emoji_smileys, "😀",
            list(
                """
                😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 🫠 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🫢 🫣 🤫 🤔 🫡
                🤐 🤨 😐 😑 😶 🫥 😶‍🌫️ 😏 😒 🙄 😬 😮‍💨 🤥 🫨 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 😵‍💫 🤯 🤠 🥳 🥸
                😎 🤓 🧐 😕 🫤 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿
                💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️‍🔥
                ❤️‍🩹 ❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🤎 🖤 🩶 🤍 💯 💢 💥 💫 💦 💨 🕳️ 💬 👁️‍🗨️ 🗨️ 🗯️ 💭 💤
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_people, "👋",
            list(
                """
                👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲
                🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦵 🦶 👂 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄 🫦 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎
                🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 🧑‍⚕️ 🧑‍🎓 🧑‍🏫 🧑‍💻 🧑‍🍳 🧑‍🎨 🧑‍🚀 👮 🕵️ 💂 🥷 👷 🤴 👸 👳 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙
                🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴️ 👯 🧖 🧗 🤺 🏇 ⛷️ 🏂 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘
                🛀 🛌 👭 👫 👬 💏 💑 👪 🗣️ 👤 👥 🫂 👣
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_nature, "🐻",
            list(
                """
                🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰
                🦟 🦗 🕷️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬
                🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐈 🐈‍⬛ 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾
                🐉 🐲 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🍄 🐚 🪨 🌾 💐 🌷 🌹 🥀 🪷 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚
                🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄
                🌬️ 💨 💧 💦 🫧 ☔ ☂️ 🌊
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_food, "🍔",
            list(
                """
                🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🫘 🥐 🥯 🍞
                🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤
                🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻
                🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_activities, "⚽",
            list(
                """
                ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 🏆 🥇 🥈
                🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎉 🎊 🎈 🎁 🎀
                🪅 🪩 🎃 🎆 🎇 🧨 ✨ 🎐 🎑 🧧
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_travel, "🚗",
            list(
                """
                🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅
                🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️
                🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍
                🛕 🕋 ⛩️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_objects, "💡",
            list(
                """
                ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📞 ☎️ 📟 📠 📺 📻 🎙️ ⏱️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦
                🕯️ 🧯 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🔮 📿
                🧿 💈 ⚗️ 🔭 🔬 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🧸
                🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃
                📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐
                📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_symbols, "💟",
            list(
                """
                ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️
                ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔
                ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿ 🅿️ 🛗 🈳 🈂️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼
                ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️
                ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰
                ♾️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️
                ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 💬 💭 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔
                """,
            ),
        ),
        EmojiCategory(
            R.string.emoji_flags, "🏳️",
            list(
                """
                🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇳 🇺🇸 🇬🇧 🇨🇦 🇦🇺 🇳🇿 🇮🇪 🇮🇳 🇵🇰 🇧🇩 🇱🇰 🇳🇵 🇨🇳 🇯🇵 🇰🇷 🇹🇼 🇭🇰 🇸🇬 🇲🇾 🇮🇩
                🇵🇭 🇹🇭 🇻🇳 🇦🇪 🇸🇦 🇶🇦 🇹🇷 🇮🇱 🇪🇬 🇳🇬 🇰🇪 🇿🇦 🇬🇭 🇪🇹 🇲🇦 🇫🇷 🇩🇪 🇪🇸 🇵🇹 🇮🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇸🇪 🇳🇴 🇩🇰
                🇫🇮 🇮🇸 🇵🇱 🇨🇿 🇺🇦 🇷🇴 🇬🇷 🇭🇺 🇷🇺 🇪🇺 🇲🇽 🇧🇷 🇦🇷 🇨🇴 🇨🇱 🇵🇪 🇻🇪 🇨🇺 🇯🇲
                """,
            ),
        ),
    )

    /** Emoji the strip offers next to a typed word. */
    private val forWord: Map<String, List<String>> = mapOf(
        "love" to listOf("❤️", "😍", "🥰"), "heart" to listOf("❤️", "💖"), "happy" to listOf("😊", "😄"),
        "sad" to listOf("😢", "😞"), "cry" to listOf("😭", "😢"), "crying" to listOf("😭"), "laugh" to listOf("😂", "🤣"),
        "lol" to listOf("😂", "🤣"), "lmao" to listOf("🤣", "😂"), "haha" to listOf("😂", "😆"), "funny" to listOf("😂", "🤣"),
        "angry" to listOf("😠", "😡"), "mad" to listOf("😡"), "cool" to listOf("😎", "🆒"), "wow" to listOf("😮", "🤯"),
        "omg" to listOf("😱", "😮"), "thanks" to listOf("🙏", "😊"), "thank" to listOf("🙏"), "please" to listOf("🙏", "🥺"),
        "sorry" to listOf("😔", "🙏"), "yes" to listOf("👍", "✅"), "no" to listOf("👎", "❌"), "ok" to listOf("👌", "👍"),
        "okay" to listOf("👌", "👍"), "good" to listOf("👍", "😊"), "great" to listOf("👍", "🙌"), "nice" to listOf("👌", "😎"),
        "awesome" to listOf("🙌", "🔥"), "fire" to listOf("🔥"), "lit" to listOf("🔥"), "hot" to listOf("🥵", "🔥"),
        "cold" to listOf("🥶", "❄️"), "sleep" to listOf("😴", "💤"), "tired" to listOf("😴", "🥱"), "sick" to listOf("🤒", "🤢"),
        "party" to listOf("🥳", "🎉"), "birthday" to listOf("🎂", "🥳", "🎉"), "congrats" to listOf("🎉", "🥳"),
        "congratulations" to listOf("🎉", "🥳"), "celebrate" to listOf("🎉", "🍾"), "pizza" to listOf("🍕"), "coffee" to listOf("☕"),
        "beer" to listOf("🍺", "🍻"), "wine" to listOf("🍷"), "cake" to listOf("🎂", "🍰"), "food" to listOf("🍔", "🍕"),
        "hungry" to listOf("🤤", "🍔"), "eat" to listOf("🍽️"), "burger" to listOf("🍔"), "taco" to listOf("🌮"),
        "dog" to listOf("🐶", "🐕"), "cat" to listOf("🐱", "🐈"), "sun" to listOf("☀️", "🌞"), "rain" to listOf("🌧️", "☔"),
        "snow" to listOf("❄️", "☃️"), "star" to listOf("⭐", "🌟"), "money" to listOf("💰", "💸"), "music" to listOf("🎵", "🎶"),
        "car" to listOf("🚗"), "home" to listOf("🏠"), "work" to listOf("💼", "💻"), "phone" to listOf("📱"), "call" to listOf("📞"),
        "think" to listOf("🤔"), "thinking" to listOf("🤔"), "hmm" to listOf("🤔"), "eyes" to listOf("👀"), "look" to listOf("👀"),
        "kiss" to listOf("😘", "💋"), "hug" to listOf("🤗", "🫂"), "hi" to listOf("👋"), "hello" to listOf("👋"), "hey" to listOf("👋"),
        "bye" to listOf("👋"), "strong" to listOf("💪"), "gym" to listOf("💪", "🏋️"), "run" to listOf("🏃"), "win" to listOf("🏆"),
        "dead" to listOf("💀"), "skull" to listOf("💀"), "clown" to listOf("🤡"), "ghost" to listOf("👻"), "rocket" to listOf("🚀"),
        "ship" to listOf("🚀"), "idea" to listOf("💡"), "done" to listOf("✅"), "check" to listOf("✅"), "time" to listOf("⏰"),
        "late" to listOf("⏰"), "perfect" to listOf("💯", "👌"), "hundred" to listOf("💯"), "100" to listOf("💯"),
        "pray" to listOf("🙏"), "clap" to listOf("👏"), "shock" to listOf("😱"), "scared" to listOf("😨", "😱"),
        "smile" to listOf("😊", "😁"), "wink" to listOf("😉"), "shh" to listOf("🤫"), "secret" to listOf("🤫"),
        "facepalm" to listOf("🤦"), "shrug" to listOf("🤷"), "flower" to listOf("🌸", "🌹"), "gift" to listOf("🎁"),
        "christmas" to listOf("🎄", "🎅"), "football" to listOf("⚽", "🏈"), "soccer" to listOf("⚽"), "game" to listOf("🎮"),
        "book" to listOf("📚", "📖"), "study" to listOf("📚"), "plane" to listOf("✈️"), "travel" to listOf("✈️", "🌍"),
        "beach" to listOf("🏖️"), "world" to listOf("🌍"), "moon" to listOf("🌙"), "night" to listOf("🌙"), "morning" to listOf("☀️"),
    )

    fun forWord(word: String): List<String> = forWord[word.lowercase()].orEmpty()
}
