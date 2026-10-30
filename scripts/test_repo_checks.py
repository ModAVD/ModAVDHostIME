import unittest

from scripts import repo_checks


class RepoChecksTest(unittest.TestCase):
    def test_allowed_identities(self):
        self.assertTrue(repo_checks.identity_allowed("qiyinxi", "qiyinxi@foxmail.com"))
        self.assertTrue(repo_checks.identity_allowed("Bot", "123+bot[bot]@users.noreply.github.com"))
        self.assertTrue(repo_checks.identity_allowed("Old", "octocat@users.noreply.github.com"))

    def test_rejects_private_or_ai_identities(self):
        self.assertFalse(repo_checks.identity_allowed("Someone", "someone@example.com"))
        self.assertFalse(repo_checks.identity_allowed("Codex", "noreply@openai.com"))
        self.assertFalse(repo_checks.identity_allowed("Claude", "noreply@" + "anthropic.com"))

    def test_commit_and_trailer_checks(self):
        good = [("a" * 40, "qiyinxi", "qiyinxi@foxmail.com", "说明\n\nCo-authored-by: Bot <123+bot[bot]@users.noreply.github.com>\n")]
        self.assertEqual(repo_checks.check_commits(good), [])
        bad = [("b" * 40, "qiyinxi", "qiyinxi@foxmail.com", "说明\n\nCo-authored-by: AI <noreply@openai.com>\n")]
        self.assertEqual(len(repo_checks.check_commits(bad),), 1)


if __name__ == "__main__":
    unittest.main()
