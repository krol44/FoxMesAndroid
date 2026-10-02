FROM gradle:8.13-jdk17

ENV ANDROID_CMDLINE_TOOLS_VERSION=15859902
ENV ANDROID_SDK_URL=https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip

ENV ANDROID_HOME=/usr/local/android-sdk-linux

ENV ANDROID_VERSION=36
ENV ANDROID_BUILD_TOOLS_VERSION=36.0.0
ENV ANDROID_NDK_VERSION=27.2.12479018
ENV ANDROID_CMAKE_VERSION=3.22.1

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /home/gradle/.android && \
    cd /tmp && \
    curl -fL "${ANDROID_SDK_URL}" -o commandlinetools.zip && \
    unzip -q commandlinetools.zip && \
    mv cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm commandlinetools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses > /dev/null
RUN sdkmanager \
    --sdk_root="${ANDROID_HOME}" \
    "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    "platforms;android-${ANDROID_VERSION}" \
    "platform-tools" \
    "ndk;${ANDROID_NDK_VERSION}" \
    "cmake;${ANDROID_CMAKE_VERSION}"

CMD set -e && \
    mkdir -p /home/gradle/src && \
    tar -C /home/source \
        --exclude=./.gradle --exclude=./.idea --exclude=./artifacts \
        --exclude=./local.properties \
        --exclude='./*/build' --exclude=./build \
        --exclude='./*/.cxx' --exclude='./TMessagesProj/jni/*/build' \
        -cf - . | tar -C /home/gradle/src -xf - && \
    cd /home/gradle/src && \
    bash foxmes/build-android.sh && \
    rm -rf /home/source/artifacts/android /home/source/artifacts/android-mapping && \
    mkdir -p /home/source/artifacts && \
    cp -R artifacts/. /home/source/artifacts/
