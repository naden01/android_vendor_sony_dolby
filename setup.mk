# Inherit dolby configs
$(call inherit-product, vendor/sony/dolby/dolby-vendor.mk)

# DolbyManager
PRODUCT_PACKAGES += \
   LunarisDolby

# SEPolicy
BOARD_VENDOR_SEPOLICY_DIRS += vendor/sony/dolby/sepolicy/vendor

# Dolby properties
PRODUCT_PRODUCT_PROPERTIES += \
    persist.vendor.audio_fx.current=dolby \
    ro.vendor.audio.dolby.dax.support=true \
    ro.vendor.dolby.dax.version=DAX3_3.7.0.8_r1 \
    ro.audio.monitorWindowRotation=true \
    vendor.audio.dolby.ds2.enabled=false \
    vendor.audio.dolby.ds2.hardbypass=false

# Audio configs
AUDIO_FEATURE_ENABLED_DS2_DOLBY_DAP := true
TARGET_EXCLUDES_AUDIOFX := true

# Permissions are installed by the LunarisDolby app module itself
# (LunarisDolby/privapp-permissions-dolby.xml -> system_ext/etc/permissions).
