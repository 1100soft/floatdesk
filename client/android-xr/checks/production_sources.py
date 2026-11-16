"""Production app source for platform-substituted JVM checks after modularization."""
from pathlib import Path
ROOT=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def application_sources():
    return '\n'.join((ROOT/name).read_text() for name in ('MainActivity.kt','WorkspaceRuntime.kt','DesktopScene.kt','WorkspaceInteraction.kt'))
