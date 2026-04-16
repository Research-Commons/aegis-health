"""Allow running the KB build pipeline as ``python -m kb.build``."""
import sys

from kb.build import main

sys.exit(main())
