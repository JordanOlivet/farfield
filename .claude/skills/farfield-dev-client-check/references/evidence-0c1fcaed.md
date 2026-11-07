[19:24:31] [Worker-Main-1/ERROR]: Caught exception in thread Thread[#96,Worker-Main-1,5,main]
java.util.ConcurrentModificationException: null
	at java.base/java.util.HashMap$ValueSpliterator.forEachRemaining(HashMap.java:1792) ~[?:?]
	at knot/com.terraformersmc.modmenu.util.UpdateCheckerUtil.checkForUpdates0(UpdateCheckerUtil.java:57) ~[modmenu-11.0.5.jar:?]