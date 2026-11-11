for p in "20000 20000" "-35000 12000" "400 -300"; do
  k=$((k+1))
  ssh lakio-vault "docker exec $C rcon-cli farfield spike heights at $p" > /dev/null 2>&1
  waitfor 'Heights spike: done' $k
  echo "heights $p done"
done