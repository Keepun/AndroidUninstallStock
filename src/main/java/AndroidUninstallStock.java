/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.SchemaFactory;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** @author Keepun */
public class AndroidUninstallStock
{
    public static class AusInfo
    {
        HashMap<String, String> apk = new HashMap<String, String>();
        LinkedList<HashMap<String, String>> include = new LinkedList<HashMap<String, String>>();
        LinkedList<HashMap<String, String>> exclude = new LinkedList<HashMap<String, String>>();
    }

    @SuppressWarnings("static-access")
    public static void main(String[] args)
    {
        try {
            String lang = Locale.getDefault().getLanguage();
            GnuParser cmdparser = new GnuParser();
            Options cmdopts = new Options();
            for (String fld : Arrays.asList("shortOpts", "longOpts", "optionGroups")) {
                // hack for printOptions
                java.lang.reflect.Field fieldopt = cmdopts.getClass().getDeclaredField(fld);
                fieldopt.setAccessible(true);
                fieldopt.set(cmdopts, new LinkedHashMap<>());
            }
            cmdopts.addOption("h", "help", false, "Help");
            cmdopts.addOption("t", "test", false, "Show only report");
            cmdopts.addOption(OptionBuilder.withLongOpt("adb").withArgName("file").hasArg()
                                           .withDescription("Path to ADB from Android SDK").create("a"));
            cmdopts.addOption(OptionBuilder.withLongOpt("dev").withArgName("device").hasArg()
                                           .withDescription("Select device (\"adb devices\")").create("d"));
            cmdopts.addOption(null, "restore", false, "If packages have not yet removed and are disabled, " +
                                                      "you can activate them again");
            cmdopts.addOption(null, "google", false, "Delete packages are in the Google section");
            cmdopts.addOption(null, "unapk", false, "Delete /system/app/ *.apk *.odex *.dex" + System.lineSeparator() +
                                                    "(It is required to repeat command execution)");
            cmdopts.addOption(null, "unlib", false, "Delete /system/lib/[libs in apk]");
            //cmdopts.addOption(null, "unfrw", false, "Delete /system/framework/ (special list)");
            cmdopts.addOption(null, "scanlibs", false, "(Dangerous!) Include all the libraries of selected packages." +
                                                       " Use with --unlib");

            cmdopts.addOptionGroup(new OptionGroup() {{
                addOption(OptionBuilder.withLongOpt("genfile").withArgName("file").hasArg().isRequired()
                                       .withDescription("Create file with list packages").create());
                addOption(OptionBuilder.withLongOpt("lang").withArgName("ISO 639").hasArg().create());
            }});
            cmdopts.getOption("lang").setDescription("See hl= in Google URL (default: " + lang +") " +
                                                     "for description from Google Play Market");
            CommandLine cmd = cmdparser.parse(cmdopts, args);

            if (args.length == 0 || cmd.hasOption("help")) {
                PrintWriter console = new PrintWriter(System.out);
                HelpFormatter cmdhelp = new HelpFormatter();
                cmdhelp.setOptionComparator(new Comparator<Option>() {
                    @Override
                    public int compare(Option o1, Option o2) {
                        return 0;
                    }
                });
                console.println("WARNING: Before use make a backup with ClockworkMod Recovery!");
                console.println();
                console.println("AndroidUninstallStock [options] [AndroidListSoft.xml]");
                cmdhelp.printOptions(console, 80, cmdopts, 3, 2);
                console.flush();
                return;
            }

            String adb = cmd.getOptionValue("adb", "adb");
            try {
                run(adb, "start-server");
            } catch (IOException e) {
                System.out.println("Error: Not found ADB! Use -a or --adb");
                return;
            }

            final boolean NotTest = !cmd.hasOption("test");

            String deverror = getDeviceStatus(adb, cmd.getOptionValue("dev"));
            if (!deverror.isEmpty()) {
                System.out.println(deverror);
                return;
            }

            System.out.println("Getting list packages:");
            LinkedHashMap<String, String> apklist = new LinkedHashMap<String, String>();
            for (String ln : run(adb, "-s", lastdevice, "shell", "pm list packages -s -f")) {
                // "pm list packages" give list sorted by packages ;)
                String pckg = ln.substring("package:".length());
                String pckgname = ln.substring(ln.lastIndexOf('=')+1);
                pckg = pckg.substring(0, pckg.length() - pckgname.length() - 1);
                if (!pckgname.equals("android") && !pckgname.equals("com.android.vending")/*Google Play Market*/) {
                    apklist.put(pckg, pckgname);
                }
            }
            for (String ln : run(adb, "-s", lastdevice, "shell", "ls /system/app/")) {
                String path = "/system/app/" + ln.replace(".odex", ".apk").replace(".dex", ".apk");
                if (!apklist.containsKey(path)) {
                    apklist.put(path, "");
                }
            }
            apklist.remove("/system/app/mcRegistry");
            for (Map.Entry<String, String> info : sortByValues(apklist).entrySet()) {
                System.out.println(info.getValue() + " = " + info.getKey());
            }

            String genfile = cmd.getOptionValue("genfile");
            if (genfile != null) {
                Path genpath = Paths.get(genfile);
                try (BufferedWriter gen = Files.newBufferedWriter(genpath, StandardCharsets.UTF_8,
                        new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                                  StandardOpenOption.WRITE})) {
                    if (cmd.getOptionValue("lang") != null) {
                        lang = cmd.getOptionValue("lang");
                    }

                    LinkedHashSet<String> listsystem = new LinkedHashSet<String>() {{
                        add("com.android");
                        add("com.google.android");
                        //add("com.sec.android.app");
                        add("com.monotype.android");
                        add("eu.chainfire.supersu");
                    }};

                    // \r\n for Windows Notepad
                    gen.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n");
                    gen.write("<!-- & raplace with &amp; or use <![CDATA[ ]]> -->\r\n");
                    gen.write("<AndroidUninstallStock>\r\n\r\n");
                    gen.write("<Normal>\r\n");
                    System.out.println();
                    System.out.println("\tNormal:");
                    writeInfo(gen, apklist, lang, listsystem, true);
                    gen.write("\t<apk name=\"Exclude Google and etc\">\r\n");
                    for (String exc : listsystem) {
                        gen.write("\t\t<exclude global=\"true\" in=\"package\" pattern=\"" + exc + "\" />\r\n");
                    }
                    gen.write("\t</apk>\r\n");
                    gen.write("</Normal>\r\n\r\n");
                    gen.write("<Google>\r\n");
                    System.out.println();
                    System.out.println("\tGoogle:");
                    writeInfo(gen, apklist, lang, listsystem, false);
                    gen.write("</Google>\r\n\r\n");
                    gen.write("</AndroidUninstallStock>\r\n");
                    System.out.println("File " + genpath.toAbsolutePath() + " created.");
                }
                return;
            }

            String[] FileName = cmd.getArgs();
            if (!(FileName.length > 0 && Files.isReadable(Paths.get(FileName[0])))) {
                System.out.println("Error: File " + FileName[0] + " not found!");
                return;
            }

            DocumentBuilderFactory xmlfactory = getXmlDocFactory();

            // DocumentBuilder.setErrorHandler() for print errors
            Document xml = xmlfactory.newDocumentBuilder().parse(new File(FileName[0]));

            LinkedList<AusInfo> Normal = new LinkedList<AusInfo>();
            LinkedList<AusInfo> Google = new LinkedList<AusInfo>();

            NodeList ndaus = xml.getElementsByTagName("AndroidUninstallStock").item(0).getChildNodes();
            for (int ndausx = 0, ndausc = ndaus.getLength(); ndausx < ndausc; ndausx++) {
                Node ndnow = ndaus.item(ndausx);
                NodeList nd = ndnow.getChildNodes();
                String ndname = ndnow.getNodeName();
                for (int ndx = 0, ndc = nd.getLength(); ndx < ndc; ndx++) {
                    if (!nd.item(ndx).getNodeName().equalsIgnoreCase("apk")) {
                        continue;
                    }
                    if (ndname.equalsIgnoreCase("Normal")) {
                        Normal.add(getApkInfo(nd.item(ndx)));
                    } else if (ndname.equalsIgnoreCase("Google")) {
                        Google.add(getApkInfo(nd.item(ndx)));
                    }
                }
            }

            // FIXME This part must be repeated until the "pm uninstall" will not issue "Failure" on all packages.
            //       Now requires a restart.
            System.out.println();
            System.out.println("Include and Exclude packages (Normal):");
            LinkedHashMap<String, String> apkNormal = getApkFromPattern(apklist, Normal, false);
            System.out.println();
            System.out.println("Global Exclude packages (Normal):");
            apkNormal = getApkFromPattern(apkNormal, Normal, true);
            System.out.println();
            System.out.println("Final list packages (Normal):");
            for (Map.Entry<String, String> info : sortByValues(apkNormal).entrySet()) {
                System.out.println(info.getValue() + " = " + info.getKey());
            }

            LinkedHashMap<String, String> apkGoogle = new LinkedHashMap<String, String>();
            if (cmd.hasOption("google")) {
                System.out.println();
                System.out.println("Include and Exclude packages (Google):");
                apkGoogle = getApkFromPattern(apklist, Google, false);
                System.out.println();
                System.out.println("Global Exclude packages (Google):");
                apkGoogle = getApkFromPattern(apkGoogle, Google, true);
                System.out.println();
                System.out.println("Final list packages (Google):");
                for (Map.Entry<String, String> info : sortByValues(apkGoogle).entrySet()) {
                    System.out.println(info.getValue() + " = " + info.getKey());
                }
            }

            if (NotTest) {
                if (!hasRoot(adb)) {
                    System.out.println("No Root");
                    System.out.println();
                    System.out.println("FINISH :)");
                    return;
                }
            }

            if (cmd.hasOption("restore")) {
                System.out.println();
                System.out.println("Enable (Restore) packages (Normal):");
                damage(adb, "pm enable ", NotTest, apkNormal, 2);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Enable (Restore) packages (Google):");
                    damage(adb, "pm enable ", NotTest, apkGoogle, 2);
                }
                System.out.println();
                System.out.println("FINISH :)");
                return;
            } else {
                System.out.println();
                System.out.println("Disable packages (Normal):");
                damage(adb, "pm disable ", NotTest, apkNormal, 2);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Disable packages (Google):");
                    damage(adb, "pm disable ", NotTest, apkGoogle, 2);
                }
            }

            if (!cmd.hasOption("unapk") && !cmd.hasOption("unlib")) {
                System.out.println();
                System.out.println("FINISH :)");
                return;
            }

            // Reboot now not needed
            /*if (NotTest) {
                reboot(adb, "-s", lastdevice, "reboot");
                if (!hasRoot(adb)) {
                    System.out.println("No Root");
                    System.out.println();
                    System.out.println("FINISH :)");
                    return;
                }
            }*/

            if (cmd.hasOption("unlib")) {
                // "find" not found
                System.out.println();
                System.out.println("Getting list libraries:");
                LinkedList<String> liblist = new LinkedList<String>();
                liblist.addAll(run(adb, "-s", lastdevice, "shell", "ls -l /system/lib/"));
                String dircur = "/system/lib/";
                for (int x = 0; x < liblist.size(); x++) {
                    if (liblist.get(x).startsWith("scan:")) {
                        dircur = liblist.get(x).substring("scan:".length());
                        liblist.remove(x);
                        x--;
                    } else if (liblist.get(x).startsWith("d")) {
                        String dir = liblist.get(x).substring(liblist.get(x).lastIndexOf(':') + 4) + "/";
                        liblist.remove(x);
                        x--;
                        liblist.add("scan:/system/lib/" + dir);
                        liblist.addAll(run(adb, "-s", lastdevice, "shell", "ls -l /system/lib/" + dir));
                        continue;
                    }
                    liblist.set(x, dircur + liblist.get(x).substring(liblist.get(x).lastIndexOf(':') + 4));
                    System.out.println(liblist.get(x));
                }

                final boolean scanlibs = cmd.hasOption("scanlibs");
                LinkedHashMap<String, String> libNormal = getLibFromPatternInclude(adb, liblist, apkNormal,
                                                                                   Normal, "Normal", scanlibs);
                libNormal = getLibFromPatternGlobalExclude(libNormal, Normal, "Normal");
                System.out.println();
                System.out.println("Final list libraries (Normal):");
                for (Map.Entry<String, String> info : sortByValues(libNormal).entrySet()) {
                    System.out.println(info.getKey() + " = " + info.getValue());
                }

                LinkedHashMap<String, String> libGoogle = new LinkedHashMap<String, String>();
                if (cmd.hasOption("google")) {
                    libGoogle = getLibFromPatternInclude(adb, liblist, apkGoogle, Google, "Google", scanlibs);
                    libGoogle = getLibFromPatternGlobalExclude(libGoogle, Google, "Google");
                    System.out.println();
                    System.out.println("Final list libraries (Google):");
                    for (Map.Entry<String, String> info : sortByValues(libGoogle).entrySet()) {
                        System.out.println(info.getKey() + " = " + info.getValue());
                    }
                }

                LinkedHashMap<String, String> apkExclude = new LinkedHashMap<String, String>(apklist);
                for (String key : apkNormal.keySet()) {
                    apkExclude.remove(key);
                }
                for (String key : apkGoogle.keySet()) {
                    apkExclude.remove(key);
                }

                System.out.println();
                System.out.println("Include libraries from Exclude packages:");
                LinkedHashMap<String, String> libExclude = getLibFromPackage(adb, liblist, apkExclude);
                System.out.println();
                System.out.println("Enclude libraries from Exclude packages (Normal):");
                for (Map.Entry<String, String> info : sortByValues(libNormal).entrySet()) {
                    if (libExclude.containsKey(info.getKey())) {
                        System.out.println("exclude: " + info.getKey() + " = " + libExclude.get(info.getKey()));
                        libNormal.remove(info.getKey());
                    }
                }
                System.out.println();
                System.out.println("Enclude libraries from Exclude packages (Google):");
                for (Map.Entry<String, String> info : sortByValues(libGoogle).entrySet()) {
                    if (libExclude.containsKey(info.getKey())) {
                        System.out.println("exclude: " + info.getKey() + " = " + libExclude.get(info.getKey()));
                        libGoogle.remove(info.getKey());
                    }
                }

                System.out.println();
                System.out.println("Delete libraries (Normal):");
                damage(adb, "rm ", NotTest, libNormal, 1);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Delete libraries (Google):");
                    damage(adb, "rm ", NotTest, libGoogle, 1);
                }
            }

            if (cmd.hasOption("unapk")) {
                System.out.println();
                System.out.println("Cleaning data packages (Normal):");
                damage(adb, "pm clear ", NotTest, apkNormal, 2);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Cleaning data packages (Google):");
                    damage(adb, "pm clear ", NotTest, apkGoogle, 2);
                }

                System.out.println();
                System.out.println("Uninstall packages (Normal):");
                damage(adb, "pm uninstall ", NotTest, apkNormal, 2);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Uninstall packages (Google):");
                    damage(adb, "pm uninstall ", NotTest, apkGoogle, 2);
                }
            }

            if (cmd.hasOption("unapk")) {
                System.out.println();
                System.out.println("Delete packages (Normal):");
                LinkedHashMap<String, String> dexNormal = new LinkedHashMap<String, String>();
                for (Map.Entry<String, String> apk : apkNormal.entrySet()) {
                    dexNormal.put(apk.getKey(), apk.getValue());
                    dexNormal.put(apk.getKey().replace(".apk", ".dex"), apk.getValue());
                    dexNormal.put(apk.getKey().replace(".apk", ".odex"), apk.getValue());
                }
                damage(adb, "rm ", NotTest, dexNormal, 1);
                if (cmd.hasOption("google")) {
                    System.out.println();
                    System.out.println("Delete packages (Google):");
                    LinkedHashMap<String, String> dexGoogle = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, String> apk : apkGoogle.entrySet()) {
                        dexGoogle.put(apk.getKey(), apk.getValue());
                        dexGoogle.put(apk.getKey().replace(".apk", ".dex"), apk.getValue());
                        dexGoogle.put(apk.getKey().replace(".apk", ".odex"), apk.getValue());
                    }
                    damage(adb, "rm ", NotTest, dexGoogle, 1);
                }
            }

            if (NotTest) {
                run(adb, "-s", lastdevice, "reboot");
            }
            System.out.println();
            System.out.println("FINISH :)");
        } catch (SAXException e) {
            System.out.println("Error parsing list: " + e);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static LinkedList<String> run(String... adb_and_args) throws IOException
    {
        Process pr = new ProcessBuilder(adb_and_args).redirectErrorStream(true).start();
        BufferedReader buf = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        LinkedList<String> res = new LinkedList<String>();
        String line;
        while ((line = buf.readLine()) != null) {
            if (!line.isEmpty()) {
                res.add(line);
            }
        }
        try {
            pr.waitFor();
        } catch (InterruptedException e) { }
        return res;
    }

    public static boolean hasRoot(String adb) throws IOException
    {
        System.out.println();
        System.out.print("Remount /system and test Root = ");
        for (String ln : run(adb, "-s", lastdevice, "shell", "su -c \"mount -o rw,remount /system ; mount\"")) {
            if (ln.indexOf("/system") > 2 && ln.indexOf("rw,") > 2) {
                System.out.println("OK");
                return true;
            }
        }
        return false;
    }

    /** Run a command with Root
     * @param keyorvalue 0=any, 1=key, 2=value
     */
    public static void damage(String adb, String command, boolean NotTest,
            LinkedHashMap<String, String> apkorliblist, int keyorvalue) throws IOException
    {
        for (Map.Entry<String, String> info :
                (keyorvalue == 1 ? sortByKeys(apkorliblist).entrySet() : sortByValues(apkorliblist).entrySet())) {
            String args;
            switch (keyorvalue) {
            case 2: args = info.getValue(); break;
            case 1: args = info.getKey(); break;
            default: args = info.getValue().isEmpty() ? info.getKey() : info.getValue(); break;
            }
            if (args.isEmpty()) {
                return;
            }
            args = command + args;
            System.out.print("# " + args);
            args = "su -c \"" + args.replace("\"", "\\\"") + "\"";
            if (NotTest) {
                System.out.println();
                for (String ln : run(adb, "-s", lastdevice, "shell", args)) {
                    System.out.println(ln);
                }
            } else {
                System.out.println(" - test");
            }
        }
    }

    public static void reboot(String adb, String... args) throws Exception
    {
        LinkedList<String> adb_and_args = new LinkedList<String>(Arrays.asList(args));
        adb_and_args.addFirst(adb);
        run(adb_and_args.toArray(new String[0]));
        String deverror, lastdeverror = "";
        while (!(deverror = getDeviceStatus(adb, null)).isEmpty()) {
            if (!lastdeverror.equals(deverror)) {
                System.out.println(deverror);
                lastdeverror = deverror;
                System.out.println("Retry... For Cancel press CTRL+C");
            }
            Thread.sleep(1000);
        }
    }

    public static LinkedHashMap<String, String> sortByKeys(Map<String, String> unsortMap)
    {
        return new LinkedHashMap<String, String>(new TreeMap<String, String>(unsortMap));
    }
    public static LinkedHashMap<String, String> sortByValues(Map<String, String> unsortMap)
    {
        // from http://www.mkyong.com/java/how-to-sort-a-map-in-java/
        LinkedList<Map.Entry<String, String>> list = new LinkedList<Map.Entry<String, String>>(unsortMap.entrySet());

        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> o1, Map.Entry<String, String> o2) {
                return ((Comparable<String>) o1.getValue())
                        .compareTo(o2.getValue());
            }
        });

        LinkedHashMap<String, String> sortedMap = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : list) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;
    }

    public static DocumentBuilderFactory getXmlDocFactory() throws SAXException
    {
        DocumentBuilderFactory xmlfactory = DocumentBuilderFactory.newInstance();
        xmlfactory.setIgnoringComments(true);
        xmlfactory.setCoalescing(true);
        // http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4867706
        xmlfactory.setIgnoringElementContentWhitespace(true);
        xmlfactory.setSchema(SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(AndroidUninstallStock.class.getResource("AndroidListSoft.xsd")));
        xmlfactory.setValidating(false); // not DTD
        return xmlfactory;
    }

    private static String lastdevice = "";
    public static String getDeviceStatus(String adb, String select) throws Exception
    {
        LinkedList<String> devices = run(adb, "devices");
        if (devices.size() < 2) {
            return "Error: No devices!";
        }

        int devindex = -1;
        String devselect = (select == null || select.isEmpty()) ? lastdevice : select;

        if (devselect.isEmpty()) {
            if (devices.size() == 2) {
                devindex = 1;
            } else {
                return "Error: Devices > 1. Disconnect excess devices or use --dev";
            }
        }
        else {
            for (int x = devices.size() - 1; x > 0 && devindex == -1; x--) {
                if (devices.get(x).indexOf(devselect) == 0) devindex = x;
            }
        }

        if (devindex > -1) {
            String[] devtemp = devices.get(devindex).split("\\t");
            if (devtemp.length != 2) {
                throw new Exception("Error: Parsing list devices");
            }
            devselect = lastdevice = devtemp[0].trim();
            if (devtemp[1].trim().equals("unauthorized")) {
                return "Error: Need authorization on device " + devselect;
            }
        } else {
            return "Error: Device " + devselect + " not found ADB!";
        }

        return "";
    }

    public static AusInfo getApkInfo(Node apk)
    {
        AusInfo apkinfo = new AusInfo();
        apkinfo.apk.putAll(getXmlAttributes(apk));

        NodeList childs = apk.getChildNodes();
        if (childs == null) {
            return apkinfo;
        }
        for (int x = 0, count = childs.getLength(); x < count; x++) {
            switch (childs.item(x).getNodeName()) {
            case "description": apkinfo.apk.put("description", childs.item(x).getTextContent()); break;
            case "include": apkinfo.include.add(getXmlAttributes(childs.item(x))); break;
            case "exclude": apkinfo.exclude.add(getXmlAttributes(childs.item(x))); break;
            default: break;
            }
        }
        return apkinfo;
    }

    public static HashMap<String, String> getXmlAttributes(Node node)
    {
        HashMap<String, String> res = new HashMap<String, String>();
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) {
            return res;
        }
        for (int x = attrs.getLength() - 1; x > -1; x--) {
            res.put(attrs.item(x).getNodeName(), attrs.item(x).getNodeValue());
        }
        return res;
    }

    public static boolean getBoolean(String value)
    {
        if (value != null &&
            (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equals("1"))) {
            return true;
        }
        return false;
    }

    private static LinkedHashMap<String, String> _getListFromPattern(LinkedHashMap<String, String> apkorliblist,
            HashMap<String, String> pattern, AusInfo info, String status, boolean library)
    {
        LinkedHashMap<String, String> res = new LinkedHashMap<String, String>();
        if (library && !pattern.get("in").equalsIgnoreCase("library")) {
            return res;
        }
        int flags = getBoolean(pattern.get("case-insensitive")) ?
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            Pattern pat = Pattern.compile(getBoolean(pattern.get("regexp")) ? pattern.get("pattern") :
                Pattern.quote(pattern.get("pattern")), flags);
            for (Map.Entry<String, String> apk : apkorliblist.entrySet()) {
                String need = "";
                switch (pattern.get("in")) {
                case "library": // TODO well as to specify the pattern package...
                case "path": need = apk.getKey(); break;
                case "path+package": need = apk.getKey() + apk.getValue(); break;
                case "apk": need = apk.getKey().substring(apk.getKey().lastIndexOf('/') + 1); break;
                case "package":
                default: need = apk.getValue(); break;
                }
                if (pat.matcher(need).find()) {
                    res.put(apk.getKey(), apk.getValue());
                    System.out.println(status +  need + " - " + pat.pattern());
                }
            }
        } catch (PatternSyntaxException e) {
            System.out.println("Warring in: " + info + " pattern: " + e);
        }
        return res;
    }
    public static LinkedHashMap<String, String> getApkFromPattern(LinkedHashMap<String, String> apklist,
            LinkedList<AusInfo> section, boolean globalexclude)
    {
        LinkedHashMap<String, String> res = new LinkedHashMap<String, String>();
        if (globalexclude) {
            res.putAll(apklist);
        }
        for (AusInfo info : section) {
            System.out.println("* " + info.apk.get("name"));
            if (!globalexclude) {
                LinkedHashMap<String, String> inc = new LinkedHashMap<String, String>();
                for (HashMap<String, String> pattern : info.include) {
                    inc.putAll(_getListFromPattern(apklist, pattern, info, "include: ", false));
                }
                for (HashMap<String, String> pattern : info.exclude) {
                    if (getBoolean(pattern.get("global"))) {
                        continue;
                    }
                    for (Map.Entry<String, String> exc :
                        _getListFromPattern(inc, pattern, info, "exclude: ", false).entrySet()) {
                            inc.remove(exc.getKey());
                    }
                }
                res.putAll(inc);
            } else {
                for (HashMap<String, String> pattern : info.exclude) {
                    if (!getBoolean(pattern.get("global"))) {
                        continue;
                    }
                    for (Map.Entry<String, String> exc :
                        _getListFromPattern(res, pattern, info, "exclude: ", false).entrySet()) {
                            res.remove(exc.getKey());
                    }
                }
            }
        }
        return res;
    }
    public static LinkedHashMap<String, String> getLibFromPackage(String adb,
            LinkedList<String> liblist, LinkedHashMap<String, String> apklist) throws IOException
    {
        LinkedHashMap<String, String> libinclude = new LinkedHashMap<String, String>();
        File libget = File.createTempFile("AndroidUninstallStockLibs", null);
        for (Map.Entry<String, String> info : sortByValues(apklist).entrySet()) {
            System.out.print("* Libs in " + info.getKey() + " (" + info.getValue() + ")");
            LinkedList<String> pull = run(adb, "-s", lastdevice, "pull", "\"" + info.getKey() +"\"",
                                                                         "\"" + libget.getCanonicalPath() +"\"");
            if (pull.size() > 0 && pull.get(0).indexOf("does not exist") > 0) {
                System.out.println(" - file not exist");
                continue;
            }
            LinkedList<String> libinapk = getLibsInApk(libget.toPath());
            if (libinapk.size() == 0) {
                System.out.println(" - empty");
            } else {
                System.out.println(":");
                for (String libpath : libinapk) {
                    String libname = libpath.substring(libpath.lastIndexOf('/') + 1);
                    boolean libfound = false;
                    for (String lb : liblist) {
                        if (lb.indexOf(libname) > -1) {
                            System.out.println(libpath + " = " + lb);
                            libinclude.put(lb,
                                           (libinclude.containsKey(libname) ? libinclude.get(libname) + ", " : "") +
                                           info.getKey());
                            libfound = true;
                        }
                    }
                    if (!libfound) {
                        System.out.println(libpath + " = not found");
                    }
                }
            }
        }
        try {
            libget.delete();
        } catch (Exception e) {}
        return libinclude;
    }
    public static LinkedHashMap<String, String> getLibFromPatternInclude(String adb,
            LinkedList<String> liblist, LinkedHashMap<String, String> apklist,
            LinkedList<AusInfo> section, String sectionname, boolean scanlibs) throws IOException
    {
        LinkedHashMap<String, String> libinclude = new LinkedHashMap<String, String>();
        if (scanlibs) {
            System.out.println();
            System.out.println("Include libraries from packages (" + sectionname + "):");
            libinclude = getLibFromPackage(adb, liblist, apklist);
        }

        System.out.println();
        System.out.println("Include libraries from section (" + sectionname + "):");
        LinkedHashMap<String, String> maplist = new LinkedHashMap<String, String>();
        for (String path : liblist) {
            maplist.put(path, "");
        }
        for (AusInfo info : section) {
            System.out.println("* " + info.apk.get("name"));
            LinkedHashMap<String, String> inc = new LinkedHashMap<String, String>();
            for (HashMap<String, String> pattern : info.include) {
                inc.putAll(_getListFromPattern(maplist, pattern, info, "include: ", true));
            }
            for (HashMap<String, String> pattern : info.exclude) {
                if (getBoolean(pattern.get("global"))) {
                    continue;
                }
                for (Map.Entry<String, String> exc :
                    _getListFromPattern(inc, pattern, info, "exclude: ", true).entrySet()) {
                        inc.remove(exc.getKey());
                }
            }
            libinclude.putAll(inc);
        }

        return libinclude;
    }
    public static LinkedHashMap<String, String> getLibFromPatternGlobalExclude(LinkedHashMap<String, String> liblist,
            LinkedList<AusInfo> section, String sectionname) throws IOException
    {
        System.out.println();
        System.out.println("Global Exclude libraries from section (" + sectionname + "):");
        for (AusInfo info : section) {
            for (HashMap<String, String> pattern : info.exclude) {
                if (!getBoolean(pattern.get("global"))) {
                    continue;
                }
                for (Map.Entry<String, String> exc :
                    _getListFromPattern(liblist, pattern, info, "exclude: ", true).entrySet()) {
                        liblist.remove(exc.getKey());
                }
            }
        }
        return liblist;
    }

    public static LinkedList<String> getLibsInApk(Path apk) throws IOException
    {
        LinkedList<String> libs = new LinkedList<String>();
        try (JarInputStream jar = new JarInputStream(Files.newInputStream(apk,
                new StandardOpenOption[] {StandardOpenOption.READ}))) {
            JarEntry jent;
            int pos;
            while ((jent = jar.getNextJarEntry()) != null) {
                if (!jent.isDirectory() && ((pos = jent.getName().indexOf("lib/")) == 0 || pos == 1)) {
                    libs.add(jent.getName());
                }
            }
        }
        return libs;
    }

    public static void writeInfo(BufferedWriter gen,
            LinkedHashMap<String, String> apklist, String lang,
            LinkedHashSet<String> listsystem, boolean normal) throws IOException
    {
        for (Map.Entry<String, String> info : sortByValues(apklist).entrySet()) {
            String pack = info.getValue(), fname = info.getKey();
            if (pack.equals("android")) {
                continue;
            }
            fname = fname.substring(fname.lastIndexOf('/') + 1);
            boolean isSystem = false;
            for (String sys : listsystem) {
                if (pack.indexOf(sys) > -1) {
                    isSystem = true;
                    break;
                }
            }
            if (normal) {
                if (isSystem || pack.isEmpty()) {
                    continue;
                }
            } else if (!isSystem && !pack.isEmpty()) {
                continue;
            }
            if (pack.isEmpty()) {
                gen.write("\t<!-- <apk name=\"" + fname + "\">\r\n");
                gen.write("\t\t<exclude global=\"true\" in=\"apk\" pattern=\"" + fname + "\" />\r\n");
                gen.write("\t</apk> -->\r\n");
                System.out.println(fname + " = Only file");
                continue;
            }
            try {
                System.out.print(pack + " = ");
                org.jsoup.nodes.Document html = Jsoup.connect(
                        "https://play.google.com/store/apps/details?hl=" + lang + "&id=" + pack).get();
                String title = html.title().substring(0, html.title().lastIndexOf('-')).trim().replaceAll("&", "&amp;");
                String desc = html.getElementsByClass("id-app-orig-desc").text();
                if (desc.length() > 150) {
                    desc = desc.substring(0, 150) + "...";
                }
                gen.write("\t<apk name=\"" + title + "\" url=\"" + html.location().replaceAll("&", "&amp;") + "\">" +
                          "\r\n");
                gen.write("\t\t<description><![CDATA[ " + desc + " ]]></description>\r\n");
                if (normal) {
                    gen.write("\t\t<!-- include in=\"apk\" pattern=\"" + fname + "\" /-->\r\n");
                    gen.write("\t\t<include in=\"package\" pattern=\"" + pack + "\" />\r\n");
                } else {
                    gen.write("\t\t<!-- exclude global=\"true\" in=\"apk\" pattern=\"" + fname + "\" /-->\r\n");
                    gen.write("\t\t<exclude global=\"true\" in=\"package\" pattern=\"" + pack + "\" />\r\n");
                }
                gen.write("\t</apk>\r\n");
                System.out.println("OK");
            } catch (Exception e) {
                gen.write("\t<apk name=\"" + pack + "\">\r\n");
                gen.write("\t\t<!-- exclude global=\"true\" in=\"apk\" pattern=\"" + fname + "\" /-->\r\n");
                gen.write("\t\t<exclude global=\"true\" in=\"package\" pattern=\"" + pack + "\" />\r\n");
                gen.write("\t</apk>\r\n");
                if (e instanceof HttpStatusException && ((HttpStatusException)e).getStatusCode() == 404) {
                    System.out.println("Not Found");
                } else {
                    System.out.println(e);
                }
            }
        }
    }
}
