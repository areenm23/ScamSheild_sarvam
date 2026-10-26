"""Stage A: deterministic, multilingual scam-indicator rules (English, Devanagari Hindi, romanized Hinglish).

Single indicators are signals, not verdicts: one keyword alone never produces a high-risk alert.
High risk requires a dangerous *combination* (e.g. credential + share request).
"""
import re
from dataclasses import dataclass, field

# indicator -> patterns. ASCII patterns get latin word boundaries; Devanagari patterns match as substrings.
INDICATORS: dict[str, list[str]] = {
    "credential": [
        r"otp", r"o\.t\.p", r"one[ -]?time[ -]?pass(word|code)?", r"verification code", r"security code",
        r"(upi |atm |card )?pin(?! ?code)", r"cvv", r"password", r"net ?banking (id|password)", r"login id",
        "ओटीपी", "ओ टी पी", "ओ.टी.पी", "पिन", "पासवर्ड", "सीवीवी", "वेरिफिकेशन कोड",
    ],
    "share_request": [
        r"share( kar(o|iye|en|ein|na)?| kijiye| karo| it| the| your)?", r"bata(iye|o|ye|ein|en|na| do| dijiye)",
        r"tell me", r"read (it )?out", r"give me", r"send (me )?(the|your)", r"de dijiye", r"bol(iye|o) (na|jaldi)?",
        r"forward (the|this)", r"type (the|it)",
        "शेयर", "बताइए", "बताइये", "बताओ", "बता दीजिए", "बता दो", "भेज दीजिए", "बोलिए",
    ],
    "urgency": [
        r"abhi", r"turant", r"jaldi", r"immediately", r"right now", r"right away", r"urgent(ly)?",
        r"within (\d+|ten|five|two) (minutes?|hours?)", r"last chance", r"before (it'?s too late|today)", r"now",
        "अभी", "तुरंत", "जल्दी", "फौरन", "फ़ौरन",
    ],
    "account_threat": [
        r"(account|khata|card|sim|number)\s+(\w+\s+){0,3}(block|blocked|band|freeze|frozen|suspend(ed)?|deactivat\w*|clos(e|ed))",
        r"(block|freeze|suspend|deactivate)\w*\s+(your|aapka|aapke)\s+(account|card|sim)",
        r"going to be blocked", r"will be (blocked|suspended|frozen|closed)",
        "खाता बंद", "अकाउंट ब्लॉक", "account block", "ब्लॉक होने", "बंद हो जाएगा", "बंद हो जायेगा", "फ्रीज",
    ],
    "bank_impersonation": [
        r"bank se", r"from (the |your )?bank", r"bank manager", r"rbi", r"reserve bank", r"customer care",
        r"(sbi|hdfc|icici|axis|kotak|pnb)( bank)?", r"talking to the bank", r"calling from (the )?bank",
        "बैंक से", "बैंक मैनेजर", "आरबीआई", "bank से",
    ],
    "authority_impersonation": [
        r"police", r"cyber ?crime", r"cbi", r"\bed\b officer", r"customs", r"narcotics", r"court", r"judge",
        r"arrest warrant", r"fir", r"(case|complaint) (hai|darj|registered|filed)", r"naam par case",
        r"income tax (department|officer)", r"crime department", r"case (against|on|in) your name", r"your name (on|in) (a )?case", r"trai", r"crime branch",
        "पुलिस", "साइबर क्राइम", "सीबीआई", "केस है", "नाम पर केस", "गिरफ्तार", "वारंट", "कोर्ट",
    ],
    "digital_arrest": [r"digital(ly)? arrest(ed)?", r"video call (pe|par|on) (raho|rahiye|stay)", "डिजिटल अरेस्ट"],
    "payment_demand": [
        r"(money|paisa|paise|amount|payment|rupees?|rs\.?)\s+(\w+\s+){0,3}(transfer|bhej\w*|send|pay|deposit)",
        r"(transfer|send|pay|deposit)\s+(\w+\s+){0,3}(money|amount|payment|rupees?|fee|fine)",
        r"settlement( amount)?", r"bhejna pad(ega|egi)", r"pay (the |a )?(fine|penalty|charges?)",
        r"upi collect", r"collect request", r"scan (the |this )?qr", r"processing fee", r"security deposit",
        "सेटलमेंट", "भेजना पड़ेगा", "भेजना पडेगा", "पैसे भेज", "ट्रांसफर", "जुर्माना", "पेमेंट", "अमाउंट भेज",
    ],
    "remote_access": [
        r"any ?desk", r"team ?viewer", r"quick ?support", r"rust ?desk", r"screen ?shar(e|ing)",
        r"(install|download) (this |the |an? )?(app|apk|application)", r"apk (file|link)",
        "एनीडेस्क", "स्क्रीन शेयर", "ऐप डाउनलोड", "ऐप इंस्टॉल",
    ],
    "kyc": [r"kyc", r"k\.y\.c", r"re-?kyc", "केवाईसी"],
    "job_fee": [
        r"registration (fee|charge)", r"joining fee", r"job (offer|confirm\w*)", r"part[ -]time job",
        r"work from home (job|offer)", r"(training|kit) fee", r"refundable deposit",
        "रजिस्ट्रेशन फीस", "जॉइनिंग फीस", "नौकरी",
    ],
    "investment_promise": [
        r"guaranteed (return|profit)s?", r"double (your )?money", r"paisa double", r"(100|hundred) ?% (profit|return)",
        r"risk[ -]free (return|profit|investment)", r"daily (profit|income) of", r"crypto (scheme|investment)",
        r"trading tips?", "पैसा डबल", "गारंटीड रिटर्न",
    ],
}

# (rule id, category, level, required indicators, score, en warning, hi warning)
COMBOS = [
    ("credential_share", "otp_phishing", "high", {"credential", "share_request"}, 90,
     "Someone is asking for a secret code or PIN. Do not share it.", "कोई गुप्त कोड या पिन मांग रहा है। इसे किसी को न बताएं।"),
    ("credential_urgency", "otp_phishing", "high", {"credential", "urgency"}, 80,
     "Urgent request involving an OTP/PIN. Banks never ask for these.", "ओटीपी/पिन के लिए जल्दबाज़ी — बैंक कभी ये नहीं मांगते।"),
    ("threat_payment", "account_threat_payment", "high", {"account_threat", "payment_demand"}, 88,
     "Threat to block your account plus a payment demand.", "खाता बंद करने की धमकी और पैसे की मांग।"),
    ("authority_payment", "fake_authority", "high", {"authority_impersonation", "payment_demand"}, 90,
     "A caller claiming to be police/officials is demanding money.", "पुलिस/अधिकारी बनकर पैसे मांगे जा रहे हैं।"),
    ("digital_arrest_any", "digital_arrest", "high", {"digital_arrest", "authority_impersonation"}, 92,
     "'Digital arrest' is not a legal procedure in India.", "'डिजिटल अरेस्ट' भारत में कोई कानूनी प्रक्रिया नहीं है।"),
    ("remote_with_bank", "remote_access", "high", {"remote_access", "bank_impersonation"}, 88,
     "Request to install a remote-access app during a 'bank' call.", "'बैंक' कॉल में रिमोट-एक्सेस ऐप इंस्टॉल करने को कहा जा रहा है।"),
    ("remote_with_payment", "remote_access", "high", {"remote_access", "payment_demand"}, 88,
     "Remote-access app combined with a payment request.", "रिमोट-एक्सेस ऐप और पैसे की मांग एक साथ।"),
    ("job_fee_payment", "job_fee_scam", "high", {"job_fee", "payment_demand"}, 82,
     "Genuine employers do not charge fees to give you a job.", "असली नौकरी देने वाले फीस नहीं लेते।"),
    ("investment_payment", "investment_scam", "high", {"investment_promise", "payment_demand"}, 82,
     "Guaranteed returns plus a payment request is a classic scam pattern.", "गारंटीड रिटर्न और पैसे की मांग — आम धोखाधड़ी का तरीका।"),
    ("bank_threat", "bank_impersonation", "medium", {"bank_impersonation", "account_threat"}, 60,
     "Caller says they are from your bank and threatens your account.", "कॉलर खुद को बैंक का बताकर खाते की धमकी दे रहा है।"),
    ("threat_urgency", "account_threat", "medium", {"account_threat", "urgency"}, 55,
     "Pressure tactic: account threat with urgency.", "दबाव की रणनीति: खाते की धमकी और जल्दबाज़ी।"),
    ("authority_case", "fake_authority", "medium", {"authority_impersonation", "urgency"}, 55,
     "Caller claims to be an official and is creating urgency.", "कॉलर अधिकारी होने का दावा कर रहा है और जल्दी करवा रहा है।"),
    ("kyc_threat", "kyc_scam", "medium", {"kyc", "account_threat"}, 60,
     "KYC update with an account threat — verify only via the official app/branch.", "केवाईसी और खाता बंद होने की धमकी — केवल आधिकारिक माध्यम से जांचें।"),
]

SINGLE_MEDIUM = {"digital_arrest", "remote_access"}  # rare enough in normal talk to warrant caution alone
LEVEL_RANK = {"low": 0, "medium": 1, "high": 2}


def _compile(pat: str) -> re.Pattern:
    if pat.isascii():
        return re.compile(rf"(?<![a-z0-9]){pat}(?![a-z0-9])", re.IGNORECASE)
    return re.compile(re.escape(pat) if not any(c in pat for c in "()|\\") else pat)


_COMPILED = {k: [_compile(p) for p in v] for k, v in INDICATORS.items()}


@dataclass
class RuleResult:
    level: str = "low"
    score: int = 0
    category: str = "none"
    rule_id: str | None = None
    indicators: dict[str, list[str]] = field(default_factory=dict)  # indicator -> exact matched phrases
    warning_en: str = ""
    warning_hi: str = ""

    def to_dict(self) -> dict:
        return {
            "source": "rules",
            "level": self.level,
            "score": self.score,
            "category": self.category,
            "rule_id": self.rule_id,
            "indicators": self.indicators,
            "evidence": [p for phrases in self.indicators.values() for p in phrases][:6],
            "warning_en": self.warning_en,
            "warning_hi": self.warning_hi,
        }


def find_indicators(text: str) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for name, pats in _COMPILED.items():
        hits = []
        for p in pats:
            for m in p.finditer(text):
                phrase = m.group(0).strip()
                if phrase and phrase.lower() not in (h.lower() for h in hits):
                    hits.append(phrase)
        if hits:
            found[name] = hits
    return found


def evaluate(text: str) -> RuleResult:
    found = find_indicators(text)
    res = RuleResult(indicators=found)
    present = set(found)
    for rule_id, category, level, needs, score, en, hi in COMBOS:
        if needs <= present and (LEVEL_RANK[level], score) > (LEVEL_RANK[res.level], res.score):
            res.level, res.score, res.category, res.rule_id = level, score, category, rule_id
            res.warning_en, res.warning_hi = en, hi
            res.indicators = {k: found[k] for k in needs}
    if res.level == "low":
        # Isolated signals: show as caution only if inherently unusual, otherwise just informational.
        risky = [k for k in found if k in SINGLE_MEDIUM]
        if risky:
            res.level, res.score, res.category = "medium", 45, risky[0]
            res.warning_en = "Unusual request detected. Be careful before acting."
            res.warning_hi = "असामान्य अनुरोध। कुछ भी करने से पहले सावधान रहें।"
        elif found:
            res.score = min(30, 10 * len(found))
            res.category = "signal_only"
    return res


# Cheap trigger for Stage B: any indicator other than generic urgency.
def is_suspicious_signal(found: dict[str, list[str]]) -> bool:
    return any(k != "urgency" for k in found)
