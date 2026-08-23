package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "",
    var lexicon: String = "",
    var ruleJieba: String = "",
    var ruleFsts: String = "",
    var ruleFars: String = "",
)
