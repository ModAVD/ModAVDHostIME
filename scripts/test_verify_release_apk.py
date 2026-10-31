import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from scripts import verify_release_apk as verify


class VerifyReleaseApkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.apksigner = os.environ.get("APKSIGNER") or shutil.which("apksigner")
        cls.keytool = os.environ.get("KEYTOOL") or shutil.which("keytool")
        if not cls.apksigner or not cls.keytool:
            raise unittest.SkipTest("需要 APKSIGNER 和 KEYTOOL 才能造临时签名样本")
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        unsigned = root / "unsigned.apk"
        with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
            zf.writestr("classes.dex", b"dex\n035\x00sample")
        cls.password = root / "pass.txt"
        cls.password.write_text("temporary-test-password\n", encoding="ascii")
        cls.keystore_a = root / "a.p12"
        cls.keystore_b = root / "b.p12"
        for ks in (cls.keystore_a, cls.keystore_b):
            subprocess.run(
                [cls.keytool, "-genkeypair", "-noprompt", "-keystore", str(ks), "-storetype", "PKCS12",
                 "-alias", "test", "-keyalg", "RSA", "-keysize", "4096", "-validity", "1",
                 "-dname", "CN=temporary-test", "-storepass:file", str(cls.password), "-keypass:file", str(cls.password)],
                check=True, capture_output=True,
            )
        cls.signed_a = root / "a.apk"
        cls.signed_b = root / "b.apk"
        for ks, out in ((cls.keystore_a, cls.signed_a), (cls.keystore_b, cls.signed_b)):
            subprocess.run(
                [cls.apksigner, "sign", "--ks", str(ks), "--ks-type", "PKCS12", "--ks-key-alias", "test",
                 "--ks-pass", f"file:{cls.password}", "--v1-signing-enabled", "false",
                 "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                 "--out", str(out), str(unsigned)],
                check=True, capture_output=True,
            )

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "tmp"):
            cls.tmp.cleanup()

    def test_different_signatures_have_same_unsigned_content(self):
        verify.compare_apks(self.signed_a.read_bytes(), self.signed_b.read_bytes())

    def test_signature_block_tamper_is_rejected(self):
        data = bytearray(self.signed_a.read_bytes())
        layout = verify.parse_layout(data)
        data[layout.central_directory - 16] ^= 1
        with self.assertRaises(ValueError):
            verify.parse_layout(bytes(data))

    def test_entry_tamper_is_rejected(self):
        data = bytearray(self.signed_b.read_bytes())
        layout = verify.parse_layout(data)
        data[layout.block_start - 1] ^= 1
        with self.assertRaises(ValueError):
            verify.compare_apks(self.signed_a.read_bytes(), bytes(data))


if __name__ == "__main__":
    unittest.main()
