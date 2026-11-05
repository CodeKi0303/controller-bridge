package dev.codek.xboxprobe;

import dev.codek.xboxprobe.core.HidIdentity;
import dev.codek.xboxprobe.core.NativeGamepadLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure verification of the key layout Android selected for one owned virtual device. */
final class AndroidOutputKeyLayout {
    static final int MAX_DUMP_BYTES=1024*1024;
    static final int MAX_LAYOUT_BYTES=64*1024;
    private static final String[] ALLOWED_DIRECTORIES={
            "/odm/usr/keylayout/","/vendor/usr/keylayout/","/system/usr/keylayout/",
            "/product/usr/keylayout/","/system_ext/usr/keylayout/","/data/system/devices/keylayout/"};
    private static final String[] BUTTON_NAMES={
            "BUTTON_A","BUTTON_B","BUTTON_C","BUTTON_X","BUTTON_Y","BUTTON_Z",
            "BUTTON_L1","BUTTON_R1","BUTTON_L2","BUTTON_R2","BUTTON_SELECT","BUTTON_START",
            "BUTTON_MODE","BUTTON_THUMBL","BUTTON_THUMBR"};
    private static final Pattern IDENTIFIER=Pattern.compile(
            "bus=0x([0-9a-fA-F]{1,4}), vendor=0x([0-9a-fA-F]{1,4}), "+
            "product=0x([0-9a-fA-F]{1,4}), version=0x([0-9a-fA-F]{1,4})"+
            "(?:, bluetoothAddress=[^\\r\\n]*)?");

    private AndroidOutputKeyLayout(){}

    /** Locate exactly one matching Event Hub record, never an Input Reader copy. */
    static String selectedPath(String dump,String ownedLocation,HidIdentity expectedIdentity) {
        checkBound(dump,MAX_DUMP_BYTES,"Input device dump");
        Objects.requireNonNull(expectedIdentity,"expectedIdentity");
        requireSingleLine(ownedLocation,"Owned location");
        requireSingleLine(expectedIdentity.getName(),"Expected device name");
        boolean inEventHub=false,ended=false;
        String recordIndent=null,fieldIndent=null;
        Pattern recordHeader=null;
        DeviceRecord current=null;
        List<DeviceRecord> matches=new ArrayList<>();
        Set<String> deviceIds=new HashSet<>();
        for(String line:dump.split("\\r?\\n",-1)) {
            String trimmed=line.trim();
            if(!inEventHub) {
                if(trimmed.equals("Event Hub State:"))inEventHub=true;
                continue;
            }
            if(trimmed.startsWith("Input Reader State:")||trimmed.startsWith("Input Reader State (")) {
                collectMatch(current,ownedLocation,matches);ended=true;break;
            }
            if(trimmed.equals("Event Hub State:"))throw invalid("Duplicate Event Hub section");
            if(trimmed.equals("Devices:")) {
                if(recordHeader!=null)throw invalid("Duplicate Event Hub device list");
                int indent=line.indexOf('D');
                if(indent<1)throw invalid("Invalid Event Hub device indentation");
                recordIndent=line.substring(0,indent)+"  ";fieldIndent=recordIndent+"  ";
                recordHeader=Pattern.compile("^"+Pattern.quote(recordIndent)+"(-?[0-9]+): (.*)$");
                continue;
            }
            if(recordHeader==null)continue;
            Matcher header=recordHeader.matcher(line);
            if(header.matches()) {
                collectMatch(current,ownedLocation,matches);
                if(!deviceIds.add(header.group(1)))throw invalid("Duplicate Event Hub device ID");
                current=new DeviceRecord(header.group(2));
            } else if(current!=null&&line.startsWith(fieldIndent)) {
                String field=line.substring(fieldIndent.length());
                if(field.startsWith("Location: "))current.locations.add(field.substring(10));
                else if(field.startsWith("Identifier: "))current.identifiers.add(field.substring(12));
                else if(field.startsWith("KeyLayoutFile: "))current.paths.add(field.substring(15));
            }
        }
        if(!inEventHub||!ended||recordHeader==null)throw invalid("Incomplete Event Hub device dump");
        if(matches.size()!=1)throw invalid("Owned output device must match exactly one Event Hub record");
        DeviceRecord selected=matches.get(0);
        if(selected.locations.size()!=1||selected.identifiers.size()!=1||selected.paths.size()!=1)
            throw invalid("Owned output device has missing or duplicate identity fields");
        if(!selected.name.equals(expectedIdentity.getName()))throw invalid("Owned output device name does not match");
        Matcher identity=IDENTIFIER.matcher(selected.identifiers.get(0));
        if(!identity.matches())throw invalid("Owned output device identifier is malformed");
        if(Integer.parseInt(identity.group(1),16)!=expectedIdentity.getBus()
                ||Integer.parseInt(identity.group(2),16)!=expectedIdentity.getVendorId()
                ||Integer.parseInt(identity.group(3),16)!=expectedIdentity.getProductId())
            throw invalid("Owned output device identity does not match");
        return checkedPath(selected.paths.get(0));
    }

    /** Validate final Android meanings rather than assuming that a filename defines them. */
    static void verifyContents(String contents,NativeGamepadLayout layout) {
        checkBound(contents,MAX_LAYOUT_BYTES,"Android key layout");
        Objects.requireNonNull(layout,"layout");
        Map<Long,String> keys=new HashMap<>(),axes=new HashMap<>();
        Set<Long> usages=new HashSet<>();
        for(String line:contents.split("\\r?\\n",-1)) {
            int comment=line.indexOf('#');
            String code=(comment<0?line:line.substring(0,comment)).trim();
            if(code.isEmpty())continue;
            String[] words=code.split("\\s+");
            switch(words[0]) {
                case "key": {
                    boolean usage=words.length>1&&words[1].equals("usage");
                    int number=usage?2:1,label=number+1;
                    if(words.length<=label)throw invalid("Malformed key entry");
                    long scan=parseCode(words[number]);
                    requireLabel(words[label]);
                    for(int i=label+1;i<words.length;i++)requireLabel(words[i]);
                    if(usage) {
                        if(!usages.add(scan))throw invalid("Duplicate HID usage key entry");
                        if(scan>=0x90001&&scan<=0x9000f)
                            throw invalid("Output button HID usage override is unsupported");
                    } else if(keys.put(scan,words[label])!=null)throw invalid("Duplicate key scan entry");
                    break;
                }
                case "axis": {
                    if(words.length!=3)throw invalid("Axis split, inversion or qualifiers are unsupported");
                    long scan=parseCode(words[1]);requireLabel(words[2]);
                    if(axes.put(scan,words[2])!=null)throw invalid("Duplicate axis entry");
                    break;
                }
                case "requires_kernel_config":
                    // The selected file from Event Hub proves this condition was accepted.
                    if(words.length!=2||!words[1].matches("CONFIG_[A-Z0-9_]+"))
                        throw invalid("Malformed kernel configuration condition");
                    break;
                case "sensor":
                case "led":
                    // These devices expose no sensors or LEDs; those mappings cannot affect input.
                    if(words.length<3)throw invalid("Malformed unrelated key layout entry");
                    break;
                default:throw invalid("Unsupported key layout statement");
            }
        }
        Map<Long,String> expectedButtons=new HashMap<>();
        for(int canonical=304;canonical<=318;canonical++) {
            if(!layout.supportsButton(canonical))continue;
            int output=layout.getOutputButtonCode(canonical);
            expectedButtons.put((long)output,BUTTON_NAMES[canonical-304]);
            if(!BUTTON_NAMES[canonical-304].equals(keys.get((long)output)))
                throw invalid("Android key layout changes output button meaning at scan "+output);
        }
        for(Map.Entry<Long,String> actual:keys.entrySet())
            if(expectedButtons.containsValue(actual.getValue())&&!expectedButtons.containsKey(actual.getKey()))
                throw invalid("Android key layout has conflicting output button semantics");
        Map<Long,String> expectedAxes=expectedAxes(layout);
        for(Map.Entry<Long,String> expected:expectedAxes.entrySet())
            if(!expected.getValue().equals(axes.get(expected.getKey())))
                throw invalid("Android key layout changes output axis meaning at scan "+expected.getKey());
        // No other axis may alias a canonical axis. This also rejects ambiguous layout edits.
        for(Map.Entry<Long,String> actual:axes.entrySet())
            if(expectedAxes.containsValue(actual.getValue())&&!expectedAxes.containsKey(actual.getKey()))
                throw invalid("Android key layout has conflicting output axis semantics");
    }

    private static Map<Long,String> expectedAxes(NativeGamepadLayout layout) {
        Map<Long,String> axes=new HashMap<>();
        axes.put(0L,"X");axes.put(1L,"Y");axes.put(16L,"HAT_X");axes.put(17L,"HAT_Y");
        if(layout==NativeGamepadLayout.SONY_PS5) {
            axes.put(3L,"Z");axes.put(4L,"RZ");axes.put(2L,"LTRIGGER");axes.put(5L,"RTRIGGER");
        } else {
            axes.put(2L,"Z");axes.put(5L,"RZ");
            axes.put(layout==NativeGamepadLayout.SONY_PS4_LEGACY?3L:10L,"LTRIGGER");
            axes.put(layout==NativeGamepadLayout.SONY_PS4_LEGACY?4L:9L,"RTRIGGER");
        }
        return axes;
    }

    private static void collectMatch(DeviceRecord record,String location,List<DeviceRecord> matches) {
        if(record!=null&&record.locations.contains(location)) {
            matches.add(record);
            if(matches.size()>1)throw invalid("Owned output location is ambiguous");
        }
    }

    private static String checkedPath(String path) {
        requireSingleLine(path,"Selected key layout path");
        if(!path.endsWith(".kl")||path.indexOf('\\')>=0||path.contains("//"))
            throw invalid("Selected key layout path is invalid");
        for(String component:path.split("/",-1))if(component.equals(".")||component.equals(".."))
            throw invalid("Selected key layout path contains traversal");
        for(String directory:ALLOWED_DIRECTORIES)
            if(path.startsWith(directory)&&path.length()>directory.length()+3)return path;
        throw invalid("Selected key layout path is outside Android key layout directories");
    }

    private static long parseCode(String value) {
        try {
            long parsed;
            if(value.matches("0[xX][0-9a-fA-F]{1,8}"))parsed=Long.parseLong(value.substring(2),16);
            else if(value.matches("[0-9]{1,10}"))parsed=Long.parseLong(value,10);
            else throw invalid("Malformed key layout numeric code");
            if(parsed>0xffffffffL)throw invalid("Key layout numeric code exceeds unsigned 32 bits");
            return parsed;
        } catch(NumberFormatException error) {throw invalid("Malformed key layout numeric code");}
    }

    private static void requireLabel(String label) {
        if(!label.matches("[A-Z][A-Z0-9_]*"))throw invalid("Malformed key layout label or unsupported axis qualifier");
    }

    private static void requireSingleLine(String value,String label) {
        if(value==null||value.isEmpty())throw invalid(label+" is missing");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)
            throw invalid(label+" contains a control character");
    }

    private static void checkBound(String value,int maxBytes,String label) {
        Objects.requireNonNull(value,label);
        if(value.length()>maxBytes)throw invalid(label+" exceeds its size limit");
        int bytes=0;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c==0)throw invalid(label+" contains NUL");
            if(c<0x80)bytes++;
            else if(c<0x800)bytes+=2;
            else if(Character.isHighSurrogate(c)) {
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))
                    throw invalid(label+" contains malformed Unicode");
                bytes+=4;
            } else if(Character.isLowSurrogate(c))throw invalid(label+" contains malformed Unicode");
            else bytes+=3;
            if(bytes>maxBytes)throw invalid(label+" exceeds its size limit");
        }
    }

    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}

    private static final class DeviceRecord {
        final String name;
        final List<String> locations=new ArrayList<>(),identifiers=new ArrayList<>(),paths=new ArrayList<>();
        DeviceRecord(String name){this.name=name;}
    }
}
