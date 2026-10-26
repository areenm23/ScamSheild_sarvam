import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.analysis import redact  # noqa: E402
from app.rules import evaluate  # noqa: E402


def test_bank_otp_romanized():
    r = evaluate("Sir, main bank se baat kar raha hoon. Aapka account block hone wala hai. Abhi OTP share kijiye.")
    assert r.level == "high" and r.category == "otp_phishing"


def test_bank_otp_codemix_devanagari():
    # Actual saaras:v4 codemix final transcript of the demo audio.
    r = evaluate("Sir मैं bank से बात कर रहा हूँ आपका account block होने वाला है अभी OTP share कीजिए।")
    assert r.level == "high"


def test_english_partial():
    r = evaluate("Sir, I'm talking to the bank. Your account is going to be blocked.")
    assert r.level == "medium" and r.category == "bank_impersonation"


def test_fake_police():
    r = evaluate("Main cyber crime department se bol raha hoon. Aapke naam par case hai. Abhi settlement amount bhejna padega.")
    assert r.level == "high" and r.category == "fake_authority"


def test_normal_is_low():
    r = evaluate("Hello, kal office meeting hai. Please 10 baje aa jana.")
    assert r.level == "low"


def test_isolated_keyword_not_high():
    for t in ["Maine OTP aane ka wait kiya", "my PIN code is near the station", "police station ke paas milte hain"]:
        assert evaluate(t).level != "high", t


def test_remote_access():
    assert evaluate("Please install AnyDesk, main bank se bol raha hoon").level == "high"
    assert evaluate("AnyDesk use karte ho?").level == "medium"


def test_redaction():
    out = redact("OTP 482913 hai, card 4111 1111 1111 1111, upi ramesh@okaxis")
    assert "482913" not in out and "4111" not in out and "ramesh@okaxis" not in out
    assert "10 baje" in redact("10 baje aa jana")


def test_english_partial_authority():
    # Real saaras:v4 auto-mode partial (English paraphrase of Hindi speech) + payment demand.
    r = evaluate("It's another crime department, I said.. I'm saying, your name on case. You have to send the settlement amount now.")
    assert r.level == "high" and r.category == "fake_authority"
