"""Fictional demo scenarios. These are scripts, not results: nothing here is a transcript or an analysis."""

SCENARIOS = {
    "bank_otp": {
        "title": "Banking OTP scam (fictional)",
        "script": "Sir, main bank se baat kar raha hoon. Aapka account block hone wala hai. Abhi OTP share kijiye.",
        # Devanagari form of the same script, used only to synthesize natural-sounding demo audio.
        "tts_text": "सर, मैं बैंक से बात कर रहा हूँ। आपका अकाउंट ब्लॉक होने वाला है। अभी ओटीपी शेयर कीजिए।",
        "speaker": "rahul",
    },
    "fake_police": {
        "title": "Fake police intimidation (fictional)",
        "script": "Main cyber crime department se bol raha hoon. Aapke naam par case hai. Abhi settlement amount bhejna padega.",
        "tts_text": "मैं साइबर क्राइम डिपार्टमेंट से बोल रहा हूँ। आपके नाम पर केस है। अभी सेटलमेंट अमाउंट भेजना पड़ेगा।",
        "speaker": "amit",
    },
    "normal": {
        "title": "Normal conversation (fictional)",
        "script": "Hello, kal office meeting hai. Please 10 baje aa jana.",
        "tts_text": "हेलो, कल ऑफिस मीटिंग है। प्लीज़ दस बजे आ जाना।",
        "speaker": "priya",
    },
}
