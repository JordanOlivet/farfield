  feed() {
    sleep 80
    echo "farfield spike tile at 30000 30000"
    sleep 45
    echo stop
  }
  feed | ./gradlew.bat :neoforge:runServer --no-daemon --console plain > "$HERE/profiles-$1.log" 2>&1
}
one a
ls -la neoforge/run/world/farfield/preview/profiles.bin >> "$HERE/profiles-a.log" 2>&1
one b