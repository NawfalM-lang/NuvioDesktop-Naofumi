"""FFsubsync uses webrtcvad-wheels, whose distribution name differs from its import."""
from PyInstaller.utils.hooks import copy_metadata

datas = copy_metadata('webrtcvad-wheels')
hiddenimports = ['_webrtcvad']
