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

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;

public class Tests
{
    @Test
    public void testXmlParser() throws Exception
    {
        final String test =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><AndroidUninstallStock><Normal>\n" +
            "    <apk name=\"NameApk\" url=\"https://play.google.com/\">\n" +
            "        <description>Desc <![CDATA[desc <desc> desc </desc> desc]]></description>\n" +
            "        <include in=\"apk\" pattern=\"^inpattern.*\" case-insensitive=\"true\" />\n" +
            "        <!-- comment -->\n" +
            "        <exclude in=\"path\" pattern=\"^expattern1.*\" case-insensitive=\"false\" />\n" +
            "        <exclude global=\"true\" in=\"apk\" pattern=\"^expattern2.*\" case-insensitive=\"true\" />\n" +
            "        <!-- exclude global - after all include+exclude in section (Normal) -->\n" +
            "    </apk>\n" +
            "</Normal></AndroidUninstallStock>\n";

        AndroidUninstallStock.AusInfo check = new AndroidUninstallStock.AusInfo();
        check.apk.putAll(new HashMap<String, String>() {{
            put("name", "NameApk");
            put("url", "https://play.google.com/");
            put("description", "Desc desc <desc> desc </desc> desc");
        }});
        check.include.add(new HashMap<String, String>() {{
            put("in", "apk");
            put("pattern", "^inpattern.*");
            put("case-insensitive", "true");
        }});
        check.exclude.add(new HashMap<String, String>() {{
            put("in", "path");
            put("pattern", "^expattern1.*");
            put("case-insensitive", "false");
        }});
        check.exclude.add(new HashMap<String, String>() {{
            put("in", "apk");
            put("pattern", "^expattern2.*");
            put("case-insensitive", "true");
            put("global", "true");
        }});

        File chbuf = File.createTempFile("AndroidUninstallStockTest", null);
        try (BufferedWriter chbufwr = Files.newBufferedWriter(chbuf.toPath(), StandardCharsets.UTF_8,
                new StandardOpenOption[] {StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE})) {
            chbufwr.write(test);
        }

        DocumentBuilderFactory xmlfactory = AndroidUninstallStock.getXmlDocFactory();
        Document xmltest = xmlfactory.newDocumentBuilder().parse(chbuf);
        AndroidUninstallStock.AusInfo result = AndroidUninstallStock.getApkInfo(xmltest.getFirstChild().getFirstChild()
                                                                                       .getFirstChild());

        assertEquals("APK", check.apk, result.apk);
        assertEquals("Include", check.include, result.include);
        assertEquals("Exclude", check.exclude, result.exclude);
    }

    @Test
    public void testLibsInApk() throws Exception
    {
        File apktest = File.createTempFile("AndroidUninstallStockTest", null);
        LinkedList<String> libs = new LinkedList<String>();
        try (JarOutputStream jarwr = new JarOutputStream(Files.newOutputStream(apktest.toPath(),
                new StandardOpenOption[] {StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}))) {
            Random rand = new Random();
            char[] symbols = "qAwSzDefVgThMy4L5O1p".toCharArray();
            for (int count = 20; count > -1; count--) {
                StringBuilder jpath = new StringBuilder();
                for (int x = rand.nextInt(2); x > -1; x--) {
                    jpath.append('/');
                    for (int y = rand.nextInt(20); y > -1; y--) {
                        jpath.append(symbols[rand.nextInt(symbols.length)]);
                    }
                }
                jpath.append(".gfgv");
                if (rand.nextBoolean()) {
                    jpath.insert(0, "/lib");
                    libs.add(jpath.toString());
                }
                jarwr.putNextEntry(new ZipEntry(jpath.toString()));
                jarwr.write(jpath.toString().getBytes());
            }
        }

        assertEquals("Libs in APK", libs, AndroidUninstallStock.getLibsInApk(apktest.toPath()));
    }
}
