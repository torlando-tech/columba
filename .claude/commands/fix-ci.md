Check the GitHub Actions CI status and fix any failures:

1. Use `gh run list --limit 1` to get the latest run
2. Use `gh run view --log` to see what failed
3. Analyze the error logs
4. Fix the issues in the code
5. Run tests locally to verify
6. Commit and push the fix
7. Monitor the new CI run with `gh run watch`
8. If it fails again, iterate until it passes
